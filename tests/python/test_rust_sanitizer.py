# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Reject false sanitizer evidence: success, arbitrary crashes, and the wrong diagnostic."""

import unittest
import xml.etree.ElementTree as ET

from tools.rust_sanitizer import verify_junit, verify_result


class SanitizerEvidenceTest(unittest.TestCase):
    def test_negative_controls_require_both_specific_diagnostic_and_exit_status(self):
        diagnostic = "LeakSanitizer: detected memory leaks"
        verify_result(86, diagnostic, diagnostic)
        for status, output in (
            (0, diagnostic),
            (-11, diagnostic),
            (1, diagnostic),
            (86, "undefined symbol: __asan_init"),
            (86, "AddressSanitizer: heap-buffer-overflow"),
        ):
            with self.subTest(status=status, output=output), self.assertRaises(ValueError):
                verify_result(status, output, diagnostic)

    def test_junit_optional_zero_counts_and_inconsistent_failure_records(self):
        def report():
            root = ET.Element("testsuites", tests="11", failures="0", errors="0")
            child = ET.SubElement(root, "testsuite", tests="11", disabled="0")
            for i in range(11):
                ET.SubElement(child, "testcase", name=str(i))
            return root

        verify_junit(report())
        explicit = report()
        explicit.set("skipped", "0")
        verify_junit(explicit)
        for key in ("failures", "errors", "skipped", "disabled", "tests"):
            for node in ("root", "child"):
                if key == "tests" and node == "child":
                    continue
                root = report()
                (root if node == "root" else root[0]).set(key, "1")
                with self.subTest(key=key, node=node), self.assertRaises(ValueError):
                    verify_junit(root)
        for key in ("failure", "error", "skipped"):
            root = report()
            ET.SubElement(root[0][0], key)
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_junit(root)
        root = report()
        root[0].remove(root[0][0])
        root.set("tests", "10")
        with self.assertRaises(ValueError):
            verify_junit(root)

    def test_clean_instrumented_commands_must_exit_successfully(self):
        verify_result(0, "")
        for status in (-11, 1, 86):
            with self.subTest(status=status), self.assertRaises(ValueError):
                verify_result(status, "failure")


if __name__ == "__main__":
    unittest.main()
