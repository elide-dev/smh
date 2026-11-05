# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Reject empty, unrelated, disabled, and malformed simulator evidence."""

import tempfile
import unittest
from pathlib import Path

from tools.test_codspeed import simulation_instructions, simulation_manifest


class CodSpeedEvidenceTest(unittest.TestCase):
    def test_manifest_is_unique_and_survives_ci_runner_banners(self):
        manifest = '[{"benchmark":"invoice","status":"pending-counter-import"}]'
        expected = [{"benchmark": "invoice", "status": "pending-counter-import"}]
        self.assertEqual(expected, simulation_manifest(manifest))
        self.assertEqual(
            expected,
            simulation_manifest(
                "CodSpeed runner v5.6.3\n::group::Running the benchmarks\n"
                + manifest
                + "\n::endgroup::\n"
            ),
        )
        for invalid in ("", "banner only", manifest + "\n" + manifest, "[malformed]"):
            with self.subTest(log=invalid), self.assertRaises((AssertionError, ValueError)):
                simulation_manifest(invalid)

    def test_only_the_named_native_process_with_positive_counters_can_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = Path("/example/benchmarks")
            dump = root / "1.out"
            valid = (
                "cmd:  /example/benchmarks config.json\n"
                "desc: Trigger: Client Request: exec_harness::invoice\n"
                "events: Ir Dr Dw\nsummary: 0\ntotals: 123 45 6\n"
                "desc: Trigger: Program termination\ntotals: 0\n"
            )
            for invalid in (
                "",
                valid.replace("totals: 123 45 6", "totals: 0"),
                valid.replace("/example/benchmarks", "/other/binary"),
                valid.replace("exec_harness::", "unknown::"),
                valid.replace("events: Ir", "events: Dr"),
                valid.replace("events: Ir Dr Dw\n", ""),
                valid.replace("totals: 123 45 6", "totals: -1 45 6"),
                valid + valid,
            ):
                with self.subTest(dump=invalid):
                    dump.write_text(invalid)
                    with self.assertRaises(AssertionError):
                        simulation_instructions(root, binary)
            dump.write_text(valid)
            self.assertEqual(123, simulation_instructions(root, binary))
