#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Build monolithic/layered calibrated AOT examples and validate their actual phase records."""

import argparse
import datetime
import hashlib
import json
import math
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(rows, config):
    expected_cases = {(size, mode) for size in ("16", "1024") for mode in ("avgt", "thrpt")}
    require(len(rows) == 4, "Expected four calibrated fixture cases")
    require(
        {(row["params"]["size"], row["mode"]) for row in rows} == expected_cases,
        "Missing or duplicate fixture case",
    )
    summary = []
    for row in rows:
        info = row["smh"]["batchCalibration"]
        for key, value in config["batchCalibration"].items():
            require(info[key] == value, "Calibration policy changed")
        batch = info["selectedBatchSize"]
        require(info["status"] == "TARGET_REACHED", "Fixture did not meet calibration target")
        require(info["stoppingReason"] == "all-probes-met-duration", "Wrong calibration reason")
        require(
            batch == row["warmupBatchSize"] == row["measurementBatchSize"],
            "Selected batch differs between phases",
        )
        attempts = info["attempts"]
        require(
            1 <= len(attempts) <= info["maximumAttempts"], "Calibration attempt budget violated"
        )
        require(
            info["invocations"] == sum(a["batchSize"] * len(a["elapsedNanos"]) for a in attempts),
            "Calibration work accounting differs",
        )
        for i, attempt in enumerate(attempts):
            require(len(attempt["elapsedNanos"]) == info["samplesPerAttempt"], "Missing probe")
            expected = (
                1 if i == 0 else min(info["maximumBatchSize"], attempts[i - 1]["batchSize"] * 2)
            )
            require(attempt["batchSize"] == expected, "Unexpected calibration batch progression")
            require(
                all(type(n) is int and n >= 0 for n in attempt["elapsedNanos"]), "Invalid duration"
            )
            require(
                (min(attempt["elapsedNanos"]) >= info["targetNanos"]) == (i == len(attempts) - 1),
                "Calibration stopped at the wrong attempt",
            )
        require(batch == attempts[-1]["batchSize"], "Selected batch was not the last tested batch")
        for phase in ("warmup", "measurement"):
            report = row["smh"][phase]
            policy = config[phase + "Stability"]
            require(report["policy"] == policy, "Stability policy changed")
            observations = report["observations"]
            count = len(observations)
            require(count == row[phase + "Iterations"], "Phase count differs from observations")
            require(
                policy["minimumSamples"] <= count <= policy["maximumSamples"],
                "Stabilization sample bounds violated",
            )
            if report["status"] == "LIMIT_REACHED":
                require(
                    count == policy["maximumSamples"]
                    and report["stoppingReason"] == "SAMPLE_LIMIT",
                    "Unstable result was hidden or stopped early",
                )
            else:
                require(
                    report["status"] == "STABLE" and report["stoppingReason"] == "CONVERGED",
                    "Invalid stabilization outcome",
                )
                require(
                    count >= policy["minimumSamples"] + policy["consecutiveWindows"] - 1,
                    "Convergence preceded the required windows",
                )
            for sample in observations:
                operations, elapsed = sample["operations"], sample["elapsedNanos"]
                require(
                    type(operations) is int and operations > 0 and operations % batch == 0,
                    "Operations do not contain complete batches",
                )
                require(type(elapsed) is int and elapsed > 0, "Invalid measured elapsed time")
                expected = (
                    elapsed / operations / 1000
                    if row["mode"] == "avgt"
                    else operations * 1000 / elapsed
                )
                require(
                    math.isclose(expected, sample["score"], rel_tol=1e-12), "Wrong sample score"
                )
        measured = row["smh"]["measurement"]["observations"]
        require(
            math.isclose(
                math.fsum(s["score"] for s in measured) / len(measured),
                row["primaryMetric"]["score"],
                rel_tol=1e-12,
            ),
            "Wrong aggregate score",
        )
        summary.append(
            {
                "size": row["params"]["size"],
                "mode": row["mode"],
                "batch": batch,
                "warmup": row["smh"]["warmup"]["status"],
                "measurement": row["smh"]["measurement"]["status"],
            }
        )
    return summary


def main():
    from build import build

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = (
        args.output
        or ROOT
        / "build/calibration-tests"
        / datetime.datetime.now(datetime.UTC).strftime("%Y%m%dT%H%M%S%fZ")
    ).resolve()
    output.mkdir(parents=True, exist_ok=False)
    build()
    snapshot = output / "snapshot"
    snapshot.mkdir()
    for name in ("smh.jar", "smh-runtime.jar"):
        shutil.copy2(ROOT / "build" / name, snapshot / name)
    shutil.copytree(ROOT / "build/lib", snapshot / "lib")
    shutil.copytree(ROOT / "examples/src", snapshot / "sources")
    cli = [
        "java",
        "-cp",
        os.pathsep.join([str(snapshot / "smh.jar"), str(snapshot / "lib/*")]),
        "dev.elide.smh.cli.Main",
    ]
    evidence = []

    def run(command, log):
        with log.open("w") as stream:
            subprocess.run(
                list(map(str, command)),
                cwd=ROOT,
                stdout=stream,
                stderr=subprocess.STDOUT,
                check=True,
                timeout=900,
            )

    for layered in (False, True):
        variant = output / ("layered" if layered else "monolithic")
        variant.mkdir()
        build_config = {
            "home": str(snapshot),
            "sources": [str(snapshot / "sources")],
            "output": str(variant / "image"),
            "optimization": "2",
            "layers": layered,
            "nativeArgs": ["--parallelism=4", "-J-Xmx4g"],
        }
        (variant / "build.json").write_text(json.dumps(build_config, indent=2) + "\n")
        run([*cli, "build", "--config", variant / "build.json"], variant / "build.log")
        config = json.loads((ROOT / "examples/calibrated-run.json").read_text())
        config["output"] = str(variant / "results.json")
        (variant / "run.json").write_text(json.dumps(config, indent=2) + "\n")
        binary = variant / "image" / ("benchmarks.exe" if os.name == "nt" else "benchmarks")
        run([binary, variant / "run.json"], variant / "run.log")
        rows = json.loads((variant / "results.json").read_text())
        evidence.append(
            {
                "variant": variant.name,
                "cases": verify(rows, config),
                "binarySha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                "build": json.loads((variant / "image/build.json").read_text()),
            }
        )
    (output / "evidence.json").write_text(
        json.dumps(
            {
                "passed": True,
                "variants": evidence,
                "scope": "Actual O2 AOT calibration/stabilization behavior and score arithmetic; not compiler/CPU border qualification",
            },
            indent=2,
        )
        + "\n"
    )
    print(output)


if __name__ == "__main__":
    main()
