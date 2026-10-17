#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/test_rgmii_io.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""RGMII functional simulation using installed Vivado UNISIM, not a board build."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
PASS = 'RGMII_1G_PASS tx_pairs=1025 rx_pairs=1025 pair_reset_cases=3'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vivado', type=Path, required=True, help='Vivado 2025.2 installation root')
    parser.add_argument('--out', type=Path, required=True, help='new output directory')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    install = args.vivado.resolve()
    sources = [ROOT / p for p in (
        'rtl/rgmii/RgmiiDigital.sv', 'rgmii/rtl/OpenEthRgmiiPairs.sv',
        'rgmii/rtl/OpenEthRgmii1G.sv', 'third_party/verilog-ethernet/iddr.v',
        'third_party/verilog-ethernet/oddr.v', 'third_party/verilog-ethernet/ssio_ddr_in.v',
        'rgmii/sim/rgmii_1g_test.sv')]
    sources.append(install / 'data/verilog/src/glbl.v')
    report = dict(status=1, started=time.time(), hardware_access=False, stages=[])

    def command(name, argv):
        result = subprocess.run([str(install / 'bin' / argv[0]), *map(str, argv[1:])],
                                cwd=out, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=300)
        (out / (name + '.log')).write_text(result.stdout)
        report['stages'].append(dict(name=name, exit_code=result.returncode))
        return result

    try:
        report['inputs'] = {str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else 'vendor/glbl.v':
                           hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
        version = command('version', ['xelab', '-version'])
        if version.returncode or '2025.2' not in version.stdout:
            raise RuntimeError('Vivado 2025.2 xelab required')
        for name, argv in (
            ('compile', ['xvlog', '--sv', *sources]),
            ('elaborate', ['xelab', '--mt', 'off', '--timescale', '1ns/1ps', '--debug', 'off',
                           '-L', 'unisims_ver', 'rgmii_1g_test', 'glbl', '-s', 'rgmii_1g_sim']),
            ('positive', ['xsim', 'rgmii_1g_sim', '-R']),
        ):
            result = command(name, argv)
            if result.returncode:
                raise RuntimeError(name + ' failed; inspect original log')
            if name == 'positive' and (PASS not in result.stdout or 'RGMII_1G_FAIL' in result.stdout):
                raise RuntimeError('missing positive result')
        for control in ('WRONG_BYTE', 'WRONG_CTL', 'UNSHIFTED_CLOCK'):
            result = command(control, ['xsim', 'rgmii_1g_sim', '-R', '-testplusarg', control])
            if 'RGMII_1G_FAIL' not in result.stdout or 'RGMII_1G_PASS' in result.stdout:
                raise RuntimeError('negative control not rejected: ' + control)
        report['status'] = 0
    except Exception as error:
        report['error'] = str(error)
    report['finished'] = time.time()
    tmp = out / 'completion.tmp'
    tmp.write_text(json.dumps(report, indent=2) + '\n')
    tmp.replace(out / 'completion.json')
    print(json.dumps(report), flush=True)
    return report['status']


if __name__ == '__main__':
    raise SystemExit(main())
