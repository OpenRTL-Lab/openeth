#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/smoke.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""Portable no-hardware copy and management-policy checks."""
import os
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    out = ROOT / 'build/smoke'
    out.mkdir(parents=True, exist_ok=True)
    cc = shlex.split(os.environ.get('CC', 'cc'))
    for source in ['tests/test_opennic_copy.c', 'tests/test_opennic_rx_merge.c', 'software/linux/auto_phy.c']:
        binary = out / Path(source).stem
        subprocess.run([*cc, '-std=gnu11', '-D_GNU_SOURCE', '-O2', '-Wall', '-Wextra', '-Werror',
                        str(ROOT / source), '-o', str(binary)], check=True)
        subprocess.run([str(binary), *(['--self-test'] if source.endswith('auto_phy.c') else [])], check=True)
    print('HOST_SMOKE_PASS hardware_access=0')


if __name__ == '__main__':
    main()
