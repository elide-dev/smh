# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Test the quality gates against incomplete, misleading, and malformed evidence."""

import contextlib
import io
import json
import random
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

from tools import coverage_gate, license_headers


class QualityToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.root_patch = patch.object(coverage_gate, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.policy = {
            "coverage": {
                group: {"line": target, "branch": target}
                for group, target in [("productionJava", 99), ("mathematicalKernel", 100)]
            }
        }
        (self.root / "quality-policy.json").write_text(json.dumps(self.policy))

    def report(self, missed=0, covered=100, include_tests=False):
        source = self.root / "src/main/java/dev/elide/smh/runtime/WindowStatistics.java"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("// fixture")
        root = ET.Element("report")
        package = ET.SubElement(root, "package", name="dev/elide/smh/runtime")
        cls = ET.SubElement(
            package,
            "class",
            name="dev/elide/smh/runtime/WindowStatistics",
            sourcefilename="WindowStatistics.java",
        )
        for kind in ["LINE", "BRANCH"]:
            ET.SubElement(cls, "counter", type=kind, missed=str(missed), covered=str(covered))
        if include_tests:
            for name in ["WindowStatisticsTest", "$GeneratedDefinition"]:
                test = ET.SubElement(
                    package,
                    "class",
                    name="dev/elide/smh/runtime/" + name,
                    sourcefilename=name + ".java",
                )
                for kind in ["LINE", "BRANCH"]:
                    ET.SubElement(test, "counter", type=kind, missed="0", covered="1000000")
        path = self.root / "coverage.xml"
        ET.ElementTree(root).write(path)
        return path

    def cli(self, report, *flags):
        output = self.root / "summary.json"
        with (
            patch("sys.argv", ["coverage_gate", str(report), "--output", str(output), *flags]),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            status = coverage_gate.main()
        return status, json.loads(output.read_text())

    def test_uncovered_production_is_not_hidden_by_generated_or_test_code(self):
        result = coverage_gate.summarize(self.report(90, 10, include_tests=True))
        self.assertEqual([90, 10], result["groups"]["productionJava"]["LINE"])
        self.assertEqual([90, 10], result["groups"]["mathematicalKernel"]["BRANCH"])
        self.assertEqual(
            {"productionJava", "generated", "test-or-generated"},
            {c["group"] for c in result["classes"]},
        )

    def test_global_kernel_and_baseline_modes(self):
        report = self.report(1, 99)
        status, summary = self.cli(report)
        self.assertEqual(1, status)
        self.assertFalse(summary["globalGatePassed"])
        status, summary = self.cli(report, "--baseline")
        self.assertEqual(0, status)
        self.assertEqual("baseline", summary["mode"])
        self.assertFalse(summary["globalGatePassed"])
        status, _ = self.cli(report, "--kernel-only")
        self.assertEqual(1, status)
        status, summary = self.cli(self.report())
        self.assertEqual(0, status)
        self.assertTrue(summary["globalGatePassed"])

    def test_missing_production_files_and_empty_evidence_fail(self):
        path = self.report()
        missing = self.root / "src/main/java/dev/elide/smh/runtime/Unreported.java"
        missing.write_text("// fixture")
        with self.assertRaisesRegex(ValueError, "omits production"):
            coverage_gate.summarize(path)
        with self.assertRaisesRegex(ValueError, "no production"):
            coverage_gate.summarize(self.report(0, 0))
        path.write_text("<report/>")
        with self.assertRaises(ValueError):
            coverage_gate.summarize(path)

    def test_duplicate_negative_and_path_traversal_evidence_fail(self):
        for variant in ["duplicate", "duplicate-counter", "negative", "traversal"]:
            path = self.report()
            tree = ET.parse(path)
            package = tree.getroot().find("package")
            cls = package.find("class")
            if variant == "duplicate":
                package.append(ET.fromstring(ET.tostring(cls)))
            elif variant == "duplicate-counter":
                cls.append(ET.fromstring(ET.tostring(cls.find("counter"))))
            elif variant == "negative":
                cls.find("counter").set("covered", "-1")
            else:
                cls.set("sourcefilename", "../../outside.java")
            tree.write(path)
            with self.assertRaises(ValueError, msg=variant):
                coverage_gate.summarize(path)

    def test_seeded_counter_properties(self):
        randomizer = random.Random(0x534D48)
        for _ in range(100):
            missed, covered = randomizer.randrange(1000), randomizer.randrange(1, 1000)
            result = coverage_gate.summarize(self.report(missed, covered))
            self.assertEqual([missed, covered], result["groups"]["productionJava"]["LINE"])

    def test_header_repair_preserves_shebang_and_body_and_is_idempotent(self):
        with patch.object(license_headers, "ROOT", self.root):
            script = self.root / "runner.py"
            script.write_text("#!/usr/bin/env python3\nprint('hello')\n")
            for flags, expected in [([], 1), (["--fix"], 0), ([], 0), (["--fix"], 0)]:
                with (
                    patch("sys.argv", ["license_headers", *flags]),
                    contextlib.redirect_stderr(io.StringIO()),
                ):
                    self.assertEqual(expected, license_headers.main())
            self.assertTrue(script.read_text().startswith("#!/usr/bin/env python3\n# SPDX"))
            self.assertTrue(script.read_text().endswith("print('hello')\n"))
            self.assertEqual(1, script.read_text().count(license_headers.LICENSE))

    def test_header_check_cannot_skip_a_production_package_named_build(self):
        with patch.object(license_headers, "ROOT", self.root):
            ignored = self.root / "build"
            ignored.mkdir()
            (ignored / "Generated.java").write_text("generated")
            source = self.root / "src/main/java/build/Owned.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Owned {}")
            selected = list(license_headers.files(self.root))
            self.assertIn(source, selected)
            self.assertNotIn(ignored / "Generated.java", selected)
            self.assertTrue(license_headers.header(source).startswith("// SPDX"))
            self.assertTrue(license_headers.header(Path("README.md")).startswith("<!-- SPDX"))


if __name__ == "__main__":
    unittest.main()
