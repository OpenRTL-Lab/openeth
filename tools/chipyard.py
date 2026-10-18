#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/chipyard.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""Prepare pinned public Chipyard inputs and generate the digital Ethernet SoC."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
URL = 'https://github.com/ucb-bar/chipyard.git'
COMMIT = '69eba860a352343e4ac6b6df0f3638a79a86ec78'
OVERLAY = {
    'nic/core/src': 'opennic/core/src',
    'nic/platform/src': 'opennic/platform/src',
    'integration/chipyard/soc': 'opennic/soc/src/main/scala/opennic/soc',
    'integration/chipyard/eth': 'generators/chipyard/src/main/scala/eth',
    'integration/chipyard/opennic-platform.sbt': 'opennic-platform.sbt',
    'integration/chipyard/opennic-soc.sbt': 'opennic-soc.sbt',
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(argv, cwd, log):
    with log.open('a') as stream:
        stream.write('COMMAND ' + repr([str(a) for a in argv]) + '\n')
        stream.flush()
        subprocess.run([str(a) for a in argv], cwd=cwd, stdout=stream,
                       stderr=subprocess.STDOUT, check=True)


def git(cwd, *args):
    return subprocess.check_output(['git', '-C', str(cwd), *args], text=True).strip()


def inputs():
    result = {}
    for prefix in ('nic/core/src', 'nic/platform/src', 'integration/chipyard'):
        for p in sorted((ROOT / prefix).rglob('*')):
            if p.is_file():
                result[p.relative_to(ROOT).as_posix()] = sha(p)
    result['tools/chipyard.py'] = sha(Path(__file__))
    return result


def overlay(chipyard):
    for src, dest in OVERLAY.items():
        target = chipyard / dest
        if target.exists():
            raise ValueError('overlay destination exists: ' + dest)
        target.parent.mkdir(parents=True, exist_ok=True)
        if (ROOT / src).is_dir():
            shutil.copytree(ROOT / src, target)
        else:
            shutil.copyfile(ROOT / src, target)


def verify_overlay(chipyard):
    for src, dest in OVERLAY.items():
        source = ROOT / src
        target = chipyard / dest
        files = sorted(source.rglob('*')) if source.is_dir() else [source]
        if source.is_dir():
            wanted = {f.relative_to(source) for f in files if f.is_file()}
            actual = {f.relative_to(target) for f in target.rglob('*') if f.is_file()}
            if actual != wanted:
                raise ValueError('overlay file set mismatch: ' + src)
        for file in files:
            if file.is_file():
                copied = target / file.relative_to(source) if source.is_dir() else target
                if not copied.is_file() or sha(copied) != sha(file):
                    raise ValueError('overlay content mismatch: ' + src)


def prepare(args, record):
    chipyard = args.work / 'chipyard'
    log = args.work / 'prepare.log'
    run(['git', '-c', 'credential.helper=', 'init', chipyard], ROOT, log)
    run(['git', 'remote', 'add', 'origin', URL], chipyard, log)
    run(['git', '-c', 'credential.helper=', 'fetch', '--depth=1', 'origin', COMMIT], chipyard, log)
    run(['git', 'checkout', '--detach', 'FETCH_HEAD'], chipyard, log)
    if git(chipyard, 'rev-parse', 'HEAD') != COMMIT:
        raise ValueError('Chipyard commit mismatch')
    names = git(chipyard, 'config', '-f', '.gitmodules', '--get-regexp', 'submodule.*.path')
    selected = [line.split()[1] for line in names.splitlines()
                if line.split()[1].startswith('generators/') or line.split()[1] in (
                    'tools/cde', 'tools/firrtl2', 'tools/dsptools', 'tools/fixedpoint',
                    'tools/rocket-dsp-utils', 'sims/firesim', 'fpga/fpga-shells')]
    # Root submodules suffice for Scala elaboration; optional accelerator RTL,
    # software and physical implementation submodules are not build inputs.
    run(['git', '-c', 'credential.helper=', 'submodule', 'update', '--init',
         '--depth=1', '--jobs=4', '--', *selected], chipyard, log)
    record['submodules'] = git(chipyard, 'submodule', 'status').splitlines()
    overlay(chipyard)
    verify_overlay(chipyard)
    if record['inputs'] != inputs():
        raise ValueError('source changed during preparation; use a new workspace')
    print('OPENETH_CHIPYARD_PREPARED', COMMIT, flush=True)


def validate_rtl(out):
    files = sorted((out / 'rtl').glob('*.sv')) + sorted((out / 'rtl').glob('*.v'))
    if not files:
        raise ValueError('no RTL generated')
    modules = {}
    for file in files:
        for name in re.findall(r'(?m)^module\s+(\w+)\b', file.read_text()):
            if name in modules:
                raise ValueError('duplicate module: ' + name)
            modules[name] = file.name
    required = ('ChipTop', 'OpenNicSystem', 'OpenNic', 'OpenNicDma',
                'DmaTileLinkBridge', 'Clause22MdioController', 'RocketTile',
                'Rocket', 'FPU', 'InclusiveCache', 'TLSPI', 'TLDebugModule')
    if any(n not in modules for n in required):
        raise ValueError('required module missing: ' + repr(set(required) - modules.keys()))
    if any('gemmini' in n.lower() or 'icenic' in n.lower() for n in modules):
        raise ValueError('unexpected accelerator or packet controller')
    top = (out / 'rtl/ChipTop.sv').read_text().split(');', 1)[0]
    for name in ('clock_uncore', 'reset_io', 'clock_tap', 'eth_0_mdio_i',
                 'eth_0_mdio_mdc', 'eth_0_phy_rstn', 'axi4_mem_0_bits_aw_bits_addr',
                 'axi4_mem_0_bits_w_bits_data', 'jtag_TCK', 'uart_0_txd'):
        if not re.search(r'\b' + name + r'\b', top):
            raise ValueError('required ChipTop port missing: ' + name)
    for direction in ('tx', 'rx'):
        for field in ('ready', 'valid', 'bits_data', 'bits_keep', 'bits_last', 'bits_error'):
            if not re.search(r'\bopennic_' + direction + '_' + field + r'\b', top):
                raise ValueError('packet stream port missing')
    dts = (out / 'OpenEth.dts').read_text()
    for pattern in (r'opennic@10016000', r'interrupts\s*=\s*<3 4>',
                    r'openhw,opennic-v1', r'openhw,openeth-management-v1'):
        if not re.search(pattern, dts):
            raise ValueError('generated address/interrupt contract mismatch: ' + pattern)
    return {'module_count': len(modules), 'required_modules': list(required),
            'outputs': {str(p.relative_to(out)): sha(p) for p in files}}


def generate(args, record):
    chipyard = args.work / 'chipyard'
    before = json.loads((args.work / 'prepare.json').read_text())
    if before['status'] != 0 or before['inputs'] != inputs():
        raise ValueError('prepared input mismatch; use a new workspace')
    if git(chipyard, 'rev-parse', 'HEAD') != COMMIT:
        raise ValueError('Chipyard commit mismatch')
    if git(chipyard, 'diff', '--name-only'):
        raise ValueError('upstream tracked sources changed')
    if git(chipyard, 'submodule', 'status').splitlines() != before['submodules']:
        raise ValueError('submodule revision mismatch')
    dirty = git(chipyard, 'submodule', 'foreach', '--quiet',
                'git diff --name-only HEAD; git ls-files --others --exclude-standard')
    if dirty:
        raise ValueError('submodule sources changed')
    verify_overlay(chipyard)
    out = args.work / 'generated'
    out.mkdir()
    log = args.work / 'generate.log'
    version = subprocess.check_output([args.firtool, '--version'], text=True)
    if 'firtool-1.75.0' not in version:
        raise ValueError('firtool 1.75.0 is required')
    record['firtool_version'] = version
    record['dtc_version'] = subprocess.check_output(['dtc', '--version'], text=True).strip()
    # testchipip supplies this small boot ROM and its rebuild sources publicly.
    boot = chipyard / 'generators/testchipip/bootrom'
    run(['make', '-C', boot, 'bootrom.rv64.img',
         'GCC=' + args.cross_compile + 'gcc',
         'OBJCOPY=' + args.cross_compile + 'objcopy'], chipyard, log)
    shutil.copyfile(boot / 'bootrom.rv64.img', out / 'bootrom.rv64.img')
    record['bootrom_sha256'] = sha(out / 'bootrom.rv64.img')
    run([args.sbt, 'opennicPlatform/test', 'opennicSoc/compile',
         'opennicSoc/runMain opennic.soc.CheckOpenEthConfig',
         'opennicSoc/runMain chipyard.Generator --target-dir ' + str(out)
         + ' --top-module chipyard.harness.TestHarness --configs '
         + 'opennic.soc.OpenEthExportConfig --name OpenEth'], chipyard, log)
    if 'OPENETH_SOC_CONFIG_PASS' not in log.read_text():
        raise ValueError('configuration check did not run')
    run([args.firtool, '--format=fir', '--verify-each=true',
         '--warn-on-unprocessed-annotations', '--disable-annotation-classless',
         '--disable-annotation-unknown',
         '--lowering-options=noAlwaysComb,disallowLocalVariables',
         '--annotation-file=' + str(out / 'OpenEth.anno.json'),
         '--split-verilog', '-o', out / 'rtl', out / 'OpenEth.fir'], chipyard, log)
    record.update(validate_rtl(out))
    if before['inputs'] != inputs():
        raise ValueError('source changed during generation')
    print('OPENETH_SOC_GENERATION_PASS', record['module_count'], flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('stage', choices=('prepare', 'generate'))
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--sbt', default='sbt')
    p.add_argument('--firtool', default='firtool')
    p.add_argument('--cross-compile', default='riscv64-unknown-elf-')
    args = p.parse_args()
    args.work = args.work.resolve()
    if args.stage == 'prepare':
        args.work.mkdir(parents=True)
    elif not args.work.is_dir():
        p.error('prepare a workspace first')
    receipt = args.work / (args.stage + '.json')
    if receipt.exists():
        p.error('receipt exists; inspect the original result, do not overwrite it')
    record = dict(status=1, stage=args.stage, started=time.time(),
                  chipyard_url=URL, chipyard_commit=COMMIT, inputs=inputs(),
                  hardware_access=False)
    try:
        globals()[args.stage](args, record)
        record['status'] = 0
    except Exception as error:
        record['error'] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        record['finished'] = time.time()
        pending = receipt.with_suffix('.pending')
        pending.write_text(json.dumps(record, indent=2) + '\n')
        pending.replace(receipt)
    return record['status']


if __name__ == '__main__':
    sys.exit(main())
