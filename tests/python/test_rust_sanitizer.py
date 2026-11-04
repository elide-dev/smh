# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Reject false sanitizer evidence: success, arbitrary crashes, and the wrong diagnostic."""

import unittest

from tools.rust_sanitizer import verify_result


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

    def test_clean_instrumented_commands_must_exit_successfully(self):
        verify_result(0, "")
        for status in (-11, 1, 86):
            with self.subTest(status=status), self.assertRaises(ValueError):
                verify_result(status, "failure")


if __name__ == "__main__":
    unittest.main()
