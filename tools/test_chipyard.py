#!/usr/bin/env python3
# OpenRTL-source-header
# Organization: OpenRTL Lab (GitHub: OpenRTL-Lab)
# Organization introduction: Open hardware, from algorithms to RTL.
# Repository: OpenRTL-Lab/openeth
# Repository introduction: Reusable 1G Ethernet RTL and OpenNIC software.
# File: tools/test_chipyard.py
# Website: https://openrtl.top/
# SPDX-License-Identifier: MIT
# OpenRTL-source-header-end
"""Host-only negative controls for public source preparation; no network or RTL run."""
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import chipyard


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        (self.source / 'module.scala').write_text('public source\n')
        self.destination = self.root / 'chipyard'
        self.destination.mkdir()
        for name, value in [('ROOT', self.root), ('OVERLAY', {'source': 'overlay'})]:
            p = patch.object(chipyard, name, value)
            p.start()
            self.addCleanup(p.stop)

    def test_exact_overlay(self):
        chipyard.overlay(self.destination)
        chipyard.verify_overlay(self.destination)

    def test_existing_overlay_rejected(self):
        chipyard.overlay(self.destination)
        with self.assertRaisesRegex(ValueError, 'destination exists'):
            chipyard.overlay(self.destination)

    def test_changed_source_rejected(self):
        chipyard.overlay(self.destination)
        (self.destination / 'overlay/module.scala').write_text('altered\n')
        with self.assertRaisesRegex(ValueError, 'content mismatch'):
            chipyard.verify_overlay(self.destination)

    def test_extra_source_rejected(self):
        chipyard.overlay(self.destination)
        (self.destination / 'overlay/extra.scala').write_text('extra\n')
        with self.assertRaisesRegex(ValueError, 'file set mismatch'):
            chipyard.verify_overlay(self.destination)

    def test_missing_source_rejected(self):
        chipyard.overlay(self.destination)
        (self.destination / 'overlay/module.scala').unlink()
        with self.assertRaises(ValueError):
            chipyard.verify_overlay(self.destination)

    def test_failed_receipt_rejected_before_tools(self):
        (self.root / 'prepare.json').write_text(json.dumps({'status': 1}))
        with patch.object(chipyard, 'git') as git:
            with self.assertRaisesRegex(ValueError, 'prepared input mismatch'):
                chipyard.generate(SimpleNamespace(work=self.root), {})
            git.assert_not_called()

    def test_input_drift_rejected_before_tools(self):
        (self.root / 'prepare.json').write_text(json.dumps({'status': 0, 'inputs': {}}))
        with patch.object(chipyard, 'inputs', return_value={'changed': 'digest'}):
            with patch.object(chipyard, 'git') as git:
                with self.assertRaisesRegex(ValueError, 'prepared input mismatch'):
                    chipyard.generate(SimpleNamespace(work=self.root), {})
                git.assert_not_called()

    def test_empty_generated_hierarchy_rejected(self):
        with self.assertRaisesRegex(ValueError, 'no RTL'):
            chipyard.validate_rtl(self.root)


if __name__ == '__main__':
    unittest.main()
