# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Missing instrumentation, source files, or trustworthy counters must fail coverage gates."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools import rust_coverage_gate as gate


class RustCoverageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "src").mkdir()
        for path in (self.root / "src/main.rs", self.root / "build.rs"):
            path.touch()
        (self.root / "quality-policy.json").write_text(
            json.dumps({"coverage": {"productionRust": {"line": 99, "branch": 99}}})
        )
        replacement = patch.object(gate, "ROOT", self.root)
        replacement.start()
        self.addCleanup(replacement.stop)

    def report(self, branches=100, missed=0):
        files = [
            {
                "filename": str(path),
                "summary": {
                    "lines": {"count": 100, "covered": 100 - missed},
                    "branches": {"count": branches, "covered": branches},
                },
            }
            for path in (self.root / "src/main.rs", self.root / "build.rs")
        ]
        path = self.root / "report.json"
        path.write_text(json.dumps({"data": [{"files": files}]}))
        return path

    def test_separate_profiles_require_real_instrumentation_and_apply_exact_thresholds(self):
        self.assertTrue(gate.summarize(self.report(branches=0))["passed"])
        with self.assertRaisesRegex(ValueError, "instrumentation"):
            gate.summarize(self.report(branches=0), branch_only=True)
        self.assertTrue(gate.summarize(self.report(missed=1))["passed"])
        self.assertFalse(gate.summarize(self.report(missed=2))["passed"])
        self.assertTrue(gate.summarize(self.report(), branch_only=True)["passed"])

    def test_missing_duplicate_and_invalid_source_counters_fail(self):
        for mode in ("missing", "duplicate", "negative", "overcount", "bool", "uninstrumented"):
            path = self.report()
            report = json.loads(path.read_text())
            files = report["data"][0]["files"]
            if mode == "missing":
                files.pop()
            elif mode == "duplicate":
                files.append(files[0])
            elif mode == "uninstrumented":
                files[0]["summary"]["lines"] = {"count": 0, "covered": 0}
            else:
                files[0]["summary"]["lines"]["covered"] = {
                    "negative": -1,
                    "overcount": 101,
                    "bool": True,
                }[mode]
            path.write_text(json.dumps(report))
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                gate.summarize(path)


if __name__ == "__main__":
    unittest.main()
