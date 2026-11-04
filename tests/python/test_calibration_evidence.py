# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Independent native calibration verifier must reject lost work and misleading outcomes."""

import copy
import json
import unittest
from pathlib import Path

from tools.test_calibration import verify


class CalibrationEvidenceTest(unittest.TestCase):
    def fixture(self):
        config = json.loads(Path("examples/calibrated-run.json").read_text())
        rows = []
        for size in ("16", "1024"):
            for mode in ("avgt", "thrpt"):
                phases = {}
                for phase in ("warmup", "measurement"):
                    phases[phase] = {
                        "policy": config[phase + "Stability"],
                        "status": "STABLE",
                        "stoppingReason": "CONVERGED",
                        "observations": [{"operations": 1, "elapsedNanos": 1000, "score": 1.0}] * 9,
                    }
                policy = config["batchCalibration"]
                phases["batchCalibration"] = {
                    **policy,
                    "status": "TARGET_REACHED",
                    "stoppingReason": "all-probes-met-duration",
                    "selectedBatchSize": 1,
                    "invocations": 3,
                    "attempts": [{"batchSize": 1, "elapsedNanos": [policy["targetNanos"]] * 3}],
                }
                rows.append(
                    {
                        "params": {"size": size},
                        "mode": mode,
                        "smh": phases,
                        "warmupBatchSize": 1,
                        "measurementBatchSize": 1,
                        "warmupIterations": 9,
                        "measurementIterations": 9,
                        "primaryMetric": {"score": 1.0},
                    }
                )
        return rows, config

    def test_accepts_both_convergence_and_honest_sample_limit(self):
        rows, config = self.fixture()
        self.assertEqual(4, len(verify(rows, config)))
        phase = rows[0]["smh"]["measurement"]
        phase.update(status="LIMIT_REACHED", stoppingReason="SAMPLE_LIMIT")
        phase["observations"] *= 3
        phase["observations"] = phase["observations"][:24]
        rows[0]["measurementIterations"] = 24
        self.assertEqual("LIMIT_REACHED", verify(rows, config)[0]["measurement"])

    def test_rejects_corrupt_phase_and_work_evidence(self):
        rows, config = self.fixture()
        mutations = [
            lambda r: r.pop(),
            lambda r: r[0].update(measurementBatchSize=2),
            lambda r: r[0]["smh"]["batchCalibration"].update(invocations=4),
            lambda r: r[0]["smh"]["batchCalibration"].update(status="EXHAUSTED"),
            lambda r: r[0]["smh"]["batchCalibration"]["attempts"][0]["elapsedNanos"].pop(),
            lambda r: r[0]["smh"]["batchCalibration"]["attempts"][0].update(batchSize=2),
            lambda r: r[0]["smh"]["measurement"].update(
                status="LIMIT_REACHED", stoppingReason="SAMPLE_LIMIT"
            ),
            lambda r: r[0]["smh"]["measurement"]["observations"][0].update(score=2),
            lambda r: r[0]["smh"]["measurement"]["observations"][0].update(operations=0),
            lambda r: r[0]["smh"]["measurement"]["observations"][0].update(elapsedNanos=-1),
            lambda r: r[0].update(measurementIterations=8),
            lambda r: r[0]["primaryMetric"].update(score=2),
        ]
        for mutation in mutations:
            corrupt = copy.deepcopy(rows)
            mutation(corrupt)
            with self.assertRaises(ValueError):
                verify(corrupt, config)


if __name__ == "__main__":
    unittest.main()
