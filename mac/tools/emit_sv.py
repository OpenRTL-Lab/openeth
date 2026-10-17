#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: mac/tools/emit_sv.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""生成不含验证探针的双时钟GMII核及RGMII数字适配。"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess

p=argparse.ArgumentParser()
p.add_argument('--out',type=Path,required=True)
p.add_argument('--max-bytes',type=int,default=2048)
a=p.parse_args()
root=Path(__file__).resolve().parents[1]
out=a.out.resolve()
if out.exists(): raise RuntimeError('输出目录必须不存在')
subprocess.run([*shlex.split(os.environ.get('MILL','mill')),'-i','mac.runMain',
                'openeth.mac.EmitMacProducts',str(out),str(a.max_bytes)],cwd=root,check=True)
print('已生成GMII核与RGMII数字适配；此入口不含ETH-01缓存、实体DDR I/O或NIC。')
