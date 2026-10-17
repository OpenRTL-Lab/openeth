#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: buffer/tools/verify.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""编译真实 RTL，执行独立模型回归及错误答案负控，原子记录完成状态。"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import traceback

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--out', required=True, type=Path)
    p.add_argument('--jobs', type=int, default=4)
    args = p.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {'status': 1, 'start': time.time(), 'cases': [], 'scope': 'ETH-01 真实RTL仿真，不含MAC/NIC或物理CDC签核'}

    def run(argv, label, expect=0):
        with (out / (label + '.log')).open('w') as log:
            result = subprocess.run(argv, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        if expect is not None and result.returncode != expect:
            raise RuntimeError(f'{label}: exit {result.returncode}; inspect log')
        return result.returncode

    try:
        verilator = shutil.which('verilator')
        if not verilator:
            raise RuntimeError('PATH 中缺少 verilator')
        report['verilator'] = subprocess.check_output([verilator, '--version'], text=True).strip()
        report['compiler'] = subprocess.check_output(['g++', '--version'], text=True).splitlines()[0]
        report['source_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        sources = [str(x) for x in sorted((ROOT / 'rtl').glob('*.sv'))]
        # Byte-width FIFO is a reusable primitive, not the product datapath.
        obj = out / 'obj-byte-primitive'
        run([verilator, '--cc', '--exe', '--build', '--assert', '-Wall', '-Wno-DECLFILENAME',
             '--top-module', 'byte_test_top', '--prefix', 'Vtest_top', '--Mdir', str(obj), '-j', str(args.jobs),
             '-GSLOTS=2', '-GMAX_FRAME_BYTES=64', '-GRX_DROP_BAD=0',
             '-CFLAGS', '-std=c++17 -DTEST_MAX=64 -DTEST_BAD=0',
             *sources, str(ROOT / 'sim/byte_test_top.sv'), str(ROOT / 'sim/check.cpp')], 'build-byte-primitive')
        run([str(obj / 'Vtest_top'), '314159'], 'byte-primitive')
        report['byte_primitive'] = (out / 'byte-primitive.log').read_text().strip()
        if 'OPENETH_ETH01_PASS ' not in report['byte_primitive']:
            raise RuntimeError('byte FIFO primitive missing PASS')
        for index, (slots, maximum, drop_bad) in enumerate([(4, 64, 1), (2, 64, 0), (4, 2048, 1), (8, 67, 1)]):
            obj = out / f'obj-{index}'
            run([verilator, '--cc', '--exe', '--build', '--assert', '-Wall', '-Wno-DECLFILENAME',
                 '--top-module', 'test_top', '--Mdir', str(obj), '-j', str(args.jobs),
                 f'-GSLOTS={slots}', f'-GMAX_FRAME_BYTES={maximum}', f'-GRX_DROP_BAD={drop_bad}',
                 '-CFLAGS', f'-std=c++17 -DTEST_MAX={maximum} -DTEST_BAD={drop_bad}',
                 *sources, str(ROOT / 'sim/test_top.sv'), str(ROOT / 'sim/check.cpp')], f'build-{index}')
            binary = obj / 'Vtest_top'
            for seed in [12345, 98765, 42]:
                label = f'case-{index}-{seed}'
                run([str(binary), str(seed)], label)
                text = (out / (label + '.log')).read_text()
                if 'OPENETH_ETH01_PASS ' not in text:
                    raise RuntimeError('missing PASS: ' + label)
                report['cases'].append({'slots': slots, 'max': maximum, 'drop_bad': drop_bad, 'seed': seed, 'result': text.strip()})
                print(text.strip(), flush=True)
            code = run([str(binary), '12345', 'inject'], f'negative-{index}', expect=None)
            evidence = (out / f'negative-{index}.log').read_text()
            if code == 0 or 'byte mismatch' not in evidence:
                raise RuntimeError('错误答案负控未被字节检查器拒绝')
        obj = out / 'obj-performance'
        run([verilator, '--cc', '--exe', '--build', '--assert', '-Wall', '-Wno-DECLFILENAME',
             '--top-module', 'test_top', '--Mdir', str(obj), '-j', str(args.jobs),
             '-GSLOTS=4', '-GMAX_FRAME_BYTES=2048', '-GRX_DROP_BAD=1',
             '-CFLAGS', '-std=c++17 -DTEST_MAX=2048 -DTEST_BAD=1',
             *sources, str(ROOT / 'sim/test_top.sv'), str(ROOT / 'sim/performance.cpp')], 'build-performance')
        run([str(obj / 'Vtest_top')], 'performance')
        performance = json.loads((out / 'performance.log').read_text())
        if performance['status'] != 0 or len(performance['rows']) != 6:
            raise RuntimeError('performance result incomplete')
        report['performance'] = performance
        report['negative_controls'] = 4
        # Export actual fixed-parameter tops and lint as a relocatable product.
        exported = out / 'exported'
        run([os.sys.executable, 'tools/emit_sv.py', '--out', str(exported), '--slots', '8',
             '--max-frame-bytes', '67', '--rx-pass-bad'], 'emit')
        for top in ['openeth_tx_buffer', 'openeth_rx_buffer']:
            with (out / ('lint-' + top + '.log')).open('w') as log:
                subprocess.run([verilator, '--lint-only', '--assert', '-Wall', '-Wno-DECLFILENAME',
                                '--top-module', top, '-f', 'rtl.f'], cwd=exported,
                               stdout=log, stderr=subprocess.STDOUT, check=True)
        report['exported_lint'] = True
        report['status'] = 0
    except Exception:
        report['traceback'] = traceback.format_exc()
        print(report['traceback'], flush=True)
    report['end'] = time.time()
    temp = out / 'completion.json.tmp'
    temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    os.replace(temp, out / 'completion.json')
    return report['status']


if __name__ == '__main__':
    raise SystemExit(main())
