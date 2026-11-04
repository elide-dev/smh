# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Bundle verification tests; actual JVM execution is exercised by the portable task."""

import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import test_portable


class PortableBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name)
        (self.bundle / "main").mkdir()
        (self.bundle / "test").mkdir()
        (self.bundle / "lib").mkdir()
        self.payload = self.bundle / "main/Fixture.class"
        self.payload.write_bytes(b"fixture")
        digest = hashlib.sha256(self.payload.read_bytes()).hexdigest()
        self.manifest = self.bundle / "SHA256SUMS"
        self.manifest.write_text(f"{digest}  main/Fixture.class\n")

    def test_tampered_and_unlisted_files_are_not_executed(self):
        with patch.object(test_portable.subprocess, "run") as child:
            self.payload.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                test_portable.execute(self.bundle)
            self.payload.write_bytes(b"fixture")
            (self.bundle / "lib/unlisted.jar").write_bytes(b"unlisted")
            with self.assertRaisesRegex(ValueError, "inventory"):
                test_portable.execute(self.bundle)
            child.assert_not_called()

    def test_traversal_and_duplicate_manifest_entries_fail(self):
        original = self.manifest.read_text()
        with patch.object(test_portable.subprocess, "run") as child:
            self.manifest.write_text(original + original)
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                test_portable.execute(self.bundle)
            self.manifest.write_text("hash  ../outside\n")
            with self.assertRaisesRegex(ValueError, "Invalid"):
                test_portable.execute(self.bundle)
            child.assert_not_called()

    def test_verified_inventory_uses_portable_tag_and_requires_tests(self):
        with patch.object(test_portable.subprocess, "run") as child:
            test_portable.execute(self.bundle)
            command = child.call_args.args[0]
            self.assertIn("--include-tag=portable", command)
            self.assertIn("--fail-if-no-tests", command)
            self.assertTrue(child.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
