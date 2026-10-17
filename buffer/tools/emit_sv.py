#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: buffer/tools/emit_sv.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""按尺寸导出可独立集成的原创 SV，不携带环境依赖或本机路径。"""
import argparse
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--out', required=True, type=Path)
p.add_argument('--slots', type=int, default=4)
p.add_argument('--max-frame-bytes', type=int, default=2048)
p.add_argument('--rx-pass-bad', action='store_true')
args = p.parse_args()
if args.slots < 2 or args.slots & (args.slots - 1) or args.max_frame_bytes < 1:
    p.error('slots 必须是至少2的2次幂；max-frame-bytes 必须为正')
args.out.mkdir(parents=True, exist_ok=False)
sources = []
for source in sorted((ROOT / 'rtl').glob('*.sv')):
    shutil.copyfile(source, args.out / source.name)
    sources.append(source.name)
wrappers = []
for direction in ['tx', 'rx']:
    wide_input = direction == 'tx'
    ports = ['input logic nic_clk, mac_clk, nic_reset_n, mac_reset_n',
             'input logic s_valid, output logic s_ready',
             'input logic [' + ('63' if wide_input else '7') + ':0] s_data',
             *(['input logic [7:0] s_keep'] if wide_input else []),
             'input logic s_last, s_error',
             'output logic m_valid, input logic m_ready',
             'output logic [' + ('7' if wide_input else '63') + ':0] m_data',
             *([] if wide_input else ['output logic [7:0] m_keep']),
             'output logic m_last, m_error',
             'output logic frame_committed, frame_dropped, overflow, bad_frame, oversize']
    parameters = f'.SLOTS({args.slots}),.MAX_FRAME_BYTES({args.max_frame_bytes})'
    if not wide_input:
        parameters += f',.DROP_BAD({int(not args.rx_pass_bad)})'
    wrappers.append(f'module openeth_{direction}_buffer (\n    ' + ',\n    '.join(ports)
                    + f'\n);\n    openeth_{direction}_cdc #({parameters}) impl (.*);\nendmodule\n')
(args.out / 'openeth_configured.sv').write_text('// SPDX-License-Identifier: MIT\n' + '\n'.join(wrappers))
sources.append('openeth_configured.sv')
(args.out / 'rtl.f').write_text('\n'.join(sources) + '\n')
config = {'slots': args.slots, 'max_frame_bytes': args.max_frame_bytes,
          'rx_drop_bad': not args.rx_pass_bad,
          'tx_top': 'openeth_tx_buffer', 'rx_top': 'openeth_rx_buffer',
          'note': '固定参数顶层；也可直接实例化参数化的 openeth_tx_cdc/openeth_rx_cdc'}
(args.out / 'config.json').write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n')
print('固定参数 SV 顶层已导出；rtl.f 使用相对文件名，参数见 config.json。')
