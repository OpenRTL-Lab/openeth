#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/test_soc_link.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""Host checks for connection generation; not RTL or board simulation."""
import unittest
from soc_link import ports, contract, emit


def fixture():
    result = {
        'clock_uncore': ('input', ''), 'reset_io': ('input', ''),
        'eth_0_mac_speed': ('input', '[1:0]'),
        'axi4_mem_0_bits_w_bits_data': ('output', '[511:0]'),
        'axi4_mem_0_bits_r_bits_data': ('input', '[511:0]'),
    }
    for f in ('rx_bad_fcs', 'rx_bad_frame', 'rx_good_frame',
              'tx_bad_frame', 'tx_good_frame', 'tx_underflow'):
        result['eth_0_mac_' + f] = ('input', '')
    for d in ('tx', 'rx'):
        for f, w in (('tdata', '[7:0]'), ('tvalid', ''), ('tready', ''), ('tlast', '')):
            result['eth_0_' + d + '_axis_' + f] = (
                'input' if (d == 'tx') == (f == 'tready') else 'output', w)
    for d in ('tx', 'rx'):
        for f, w in (('ready', ''), ('valid', ''), ('bits_data', '[63:0]'),
                     ('bits_keep', '[7:0]'), ('bits_last', ''), ('bits_error', '')):
            result['opennic_' + d + '_' + f] = (
                'input' if (d == 'tx') == (f == 'ready') else 'output', w)
    for i in range(102 - len(result)):
        result['external_' + str(i)] = ('input', '')
    return result


class LinkTests(unittest.TestCase):
    def test_parse_inherited_direction_and_width(self):
        self.assertEqual(ports('module ChipTop(input [63:0] a, b, output c);'),
                         {'a': ('input', '[63:0]'), 'b': ('input', '[63:0]'),
                          'c': ('output', '')})

    def test_comments_removed(self):
        self.assertEqual(ports('module ChipTop( // unused\n input a /* hi */);'),
                         {'a': ('input', '')})

    def test_duplicate_rejected(self):
        with self.assertRaises(ValueError):
            ports('module ChipTop(input a, output a);')

    def test_unsupported_declaration_rejected(self):
        with self.assertRaises(ValueError):
            ports('module ChipTop(inout pad);')

    def test_complete_mapping(self):
        p = fixture()
        text = emit(p)
        for name in p:
            self.assertEqual(text.count('.' + name + '(' + name + ')'), 1)
        for d in ('tx', 'rx'):
            for f in ('data', 'keep', 'last', 'error'):
                self.assertIn('.' + d + '_' + f + '(opennic_' + d + '_bits_' + f + ')', text)
        self.assertIn('.nic_clk(clock_uncore)', text)
        self.assertIn('.sys_reset(reset_io)', text)

    def test_width_corruption_rejected(self):
        p = fixture()
        p['opennic_tx_bits_data'] = ('output', '[31:0]')
        with self.assertRaisesRegex(ValueError, 'port contract mismatch'):
            emit(p)

    def test_direction_corruption_rejected(self):
        p = fixture()
        p['opennic_rx_ready'] = ('input', '')
        with self.assertRaises(ValueError):
            contract(p)

    def test_missing_port_rejected(self):
        p = fixture()
        del p['reset_io']
        with self.assertRaises(ValueError):
            emit(p)

    def test_extra_port_rejected(self):
        p = fixture()
        p['unexpected'] = ('input', '')
        with self.assertRaisesRegex(ValueError, '102-port'):
            emit(p)

    def test_missing_management_port_rejected(self):
        p = fixture()
        del p['eth_0_rx_axis_tvalid']
        p['replacement'] = ('input', '')
        with self.assertRaisesRegex(ValueError, 'port contract mismatch'):
            emit(p)

    def test_unexpected_stream_field_rejected(self):
        p = fixture()
        del p['external_0']
        p['opennic_unexpected'] = ('input', '')
        with self.assertRaisesRegex(ValueError, 'unexpected internal port'):
            emit(p)


if __name__ == '__main__':
    unittest.main()
