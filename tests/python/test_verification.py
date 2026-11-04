# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""The verifier must still reject bad evidence under optimized Python execution."""

import subprocess
import sys
import unittest

from tools.verification import require


class VerificationTest(unittest.TestCase):
    def test_checks_are_not_removed_by_python_optimizer(self):
        require(True)
        result = subprocess.run(
            [
                sys.executable,
                "-O",
                "-c",
                "from tools.verification import require; require(False, 'bad-evidence')",
            ],
            text=True,
            capture_output=True,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("bad-evidence", result.stderr)


if __name__ == "__main__":
    unittest.main()
