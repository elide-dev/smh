#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Real CodSpeed exec-harness integration; never uploads or substitutes a simulator."""

import argparse
import datetime
import hashlib
import json
import os
import platform
import shutil
from pathlib import Path

if __package__:
    from .build import ROOT
    from .test_invoice import APP, build_and_test, logged
    from .verification import require
else:
    from test_invoice import APP, build_and_test, logged

    from build import ROOT
    from verification import require


def simulation_instructions(profile, binary):
    """Require a named exec-harness region in the actual benchmark process with real work."""
    counts = []
    for dump in profile.glob("*.out"):
        lines = dump.read_text().splitlines()
        if not any(line.startswith(f"cmd:  {binary} ") for line in lines):
            continue
        measured = False
        instructions_first = False
        for line in lines:
            if line.startswith("desc: Trigger:"):
                measured = line.startswith("desc: Trigger: Client Request: exec_harness::")
                instructions_first = False
            elif measured and line.startswith("events:"):
                instructions_first = line.split()[1:] and line.split()[1] == "Ir"
                require(instructions_first, "Expected instruction count as first event")
            elif measured and line.startswith("totals:"):
                require(instructions_first, "Missing instruction event declaration")
                values = [int(value) for value in line.split()[1:]]
                require(values and all(value >= 0 for value in values), "Invalid simulator totals")
                counts.append(values[0])
    require(
        len(counts) == 1 and counts[0] > 0,
        "Expected one named native-process region with positive simulated instructions",
    )
    return counts[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codspeed", default="codspeed")
    parser.add_argument("--binary", type=Path, help="Reuse an already built invoice benchmark")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    codspeed = shutil.which(args.codspeed)
    require(codspeed is not None, "Install CodSpeed 5.6.3; no simulated test provider is used")
    binary = args.binary.resolve() if args.binary else build_and_test()
    require(binary.is_file(), f"Missing benchmark: {binary}")
    output = (
        args.output
        or ROOT
        / "build/codspeed-tests"
        / datetime.datetime.now(datetime.UTC).strftime("%Y%m%dT%H%M%S%fZ")
    ).resolve()
    output.mkdir(parents=True, exist_ok=False)
    version = logged([codspeed, "--version"], APP, output / "version.log").strip()
    require(version == "codspeed-runner 5.6.3", f"Expected tested CodSpeed 5.6.3; got {version}")
    # This is a compatibility test, not a low-noise walltime qualification. Disabling perf
    # permits local execution without altering kernel settings; timings remain real.
    os.environ["CODSPEED_PERF_ENABLED"] = "false"
    records = {}
    for mode in ("simulation", "walltime"):
        profile = output / mode
        profile.mkdir()
        command = [codspeed, "exec", "--skip-upload", "--mode", mode, "--profile-folder", profile]
        if mode == "walltime":
            command += ["--warmup-time", "0", "--max-rounds", "3"]
        command += ["--", binary, APP / "smh-fixed-work.json"]
        logged(command, APP, output / (mode + ".log"))
        # CodSpeed must actually produce nonempty instrument artifacts, not just exit zero.
        artifacts = [p for p in profile.rglob("*") if p.is_file() and p.stat().st_size]
        require(artifacts, f"Missing {mode} evidence")
        if mode == "walltime":
            reports = [json.loads(p.read_text()) for p in profile.glob("results/*.json")]
            require(reports, "Missing CodSpeed walltime results")
            benchmarks = [
                b for r in reports if r["instrument"]["type"] == "walltime" for b in r["benchmarks"]
            ]
            require(len(benchmarks) == 1, "Expected exactly one measured command")
            require(benchmarks[0]["stats"]["rounds"] == 3)
            require(benchmarks[0]["stats"]["mean_ns"] > 0)
        else:
            simulation_instructions(profile, binary)
            manifest = json.loads((output / "simulation.log").read_text())
            require(len(manifest) == 1)
            require(manifest[0]["benchmark"] == "example.invoice.InvoiceBenchmark.total")
            require(manifest[0]["invocationsPerIteration"] == 100)
            require(manifest[0]["status"] == "pending-counter-import")
        records[mode] = {
            str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in artifacts
        }
    (output / "evidence.json").write_text(
        json.dumps(
            {
                "codspeed": version,
                "binarySha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                "fixedWorkConfigSha256": hashlib.sha256(
                    (APP / "smh-fixed-work.json").read_bytes()
                ).hexdigest(),
                "host": platform.platform(),
                "build": json.loads((binary.parent / "build.json").read_text()),
                "scope": "Whole native process: startup, fixtures, 100 invocations, reporting; not per-operation scores",
                "walltimeProfiling": False,
                "simulatedInstructions": simulation_instructions(output / "simulation", binary),
                "artifacts": records,
            },
            indent=2,
        )
    )
    print("Real CodSpeed simulation and walltime integration passed:", output)


if __name__ == "__main__":
    main()
