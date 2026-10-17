#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/check_performance.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""Recompute recorded receiver statistics; never runs traffic or rewrites loss."""
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[1]


def main():
    data = json.loads((ROOT / 'evidence/performance.json').read_text())
    cases = data['cases']
    expected_names = {f'tcp_fpga_{d}_{i}' for d in ['rx', 'tx'] for i in range(1, 4)}
    expected_names |= {f'udp_fpga_rx_{r}_{i}' for r in ['18M', '20M'] for i in range(1, 4)}
    expected_names.add('tcp_full_duplex')
    if len(cases) != 13 or {c['name'] for c in cases} != expected_names:
        raise ValueError('incomplete/duplicate case coverage')
    rows = {}
    for c in cases:
        name = c['name']; setup = c['test_start']; udp = name.startswith('udp_'); bidir = name == 'tcp_full_duplex'
        contract = dict(num_streams=1, omit=2, duration=20 if udp else (60 if bidir else 30),
                        protocol='UDP' if udp else 'TCP', reverse=int('tcp_fpga_tx_' in name), bidir=int(bidir))
        if any(setup.get(k) != v for k, v in contract.items()):
            raise ValueError('measurement contract drift: ' + name)
        if udp and (setup['blksize'] != 1472 or setup['target_bitrate'] != int('18000000' if '_18M_' in name else '20000000')):
            raise ValueError('UDP rate/packet size drift')
        primary = c['end'].get('sum_received', c['end'].get('sum'))
        if not primary:
            raise ValueError('missing receiver result')
        rows[name] = {'receiver_mbps': primary['bits_per_second'] / 1e6}
        if udp:
            rows[name]['lost_percent'] = primary['lost_percent']
        if bidir:
            rows[name]['reverse_receiver_mbps'] = c['end']['sum_received_bidir_reverse']['bits_per_second'] / 1e6
    summary = {f'tcp_{d}_median_mbps': statistics.median(rows[f'tcp_fpga_{d}_{i}']['receiver_mbps'] for i in range(1, 4)) for d in ['rx', 'tx']}
    b = rows['tcp_full_duplex']
    summary.update(bidir_rx_mbps=b['receiver_mbps'], bidir_tx_mbps=b['reverse_receiver_mbps'],
                   bidir_sum_mbps=b['receiver_mbps'] + b['reverse_receiver_mbps'])
    for rate in ['18M', '20M']:
        summary['udp_' + rate + '_loss_percent'] = [rows[f'udp_fpga_rx_{rate}_{i}']['lost_percent'] for i in range(1, 4)]
    if summary != data['summary'] or data['performance_health_passed'] is not False:
        raise ValueError('summary/health drift')
    print('PERFORMANCE_RECOMPUTED', json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
