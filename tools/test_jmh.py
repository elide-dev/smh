#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Real JMH forks and strict JDK 25 Leyden cache creation, reuse, and invalidation."""

import argparse
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from build import ROOT
from verification import artifact_identity, require


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--home", type=Path, default=ROOT / "build")
    parser.add_argument("--cli", type=Path, help="Use a native CLI from an extracted distribution")
    args = parser.parse_args()
    sdk = args.home.resolve()
    home = args.java_home or Path(shutil.which("java")).resolve().parent.parent
    java = home / "bin" / ("java.exe" if os.name == "nt" else "java")
    root = ROOT / "build/jmh-tests"
    root.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix="run-", dir=root))
    source = evidence / "Work.java"
    source.write_text("""package delegated;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;
@State(Scope.Thread) @OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Work {
  @Param({"3", "7"}) public int step;
  private int state;
  @Benchmark public int work() { return state += step; }
}
""")
    settings = dict(home=str(sdk), javaHome=str(home), sources=[str(source)])
    build = evidence / "build.json"
    build.write_text(json.dumps(settings))
    config = evidence / "run.json"
    config.write_text(
        json.dumps(
            dict(
                warmupIterations=2,
                measurementIterations=3,
                warmupNanos=100_000_000,
                measurementNanos=100_000_000,
                modes=["AverageTime"],
            )
        )
    )
    launcher = (
        [str(args.cli.resolve())]
        if args.cli
        else [
            str(java),
            "-cp",
            os.pathsep.join([str(sdk / "smh.jar"), str(sdk / "lib/*")]),
            "dev.elide.smh.cli.Main",
        ]
    )
    cli = [
        *launcher,
        "jmh",
        "--build-config",
        str(build),
        "--config",
        str(config),
        "--forks",
        "2",
        "--display",
        "tui",
    ]
    keys = []
    for name, extra in [
        ("uncached", []),
        ("c2", ["--execution", "hotspot-c2"]),
        ("trained", ["--aot-cache"]),
        ("reused", ["--aot-cache"]),
        ("changed", ["--aot-cache"]),
    ]:
        if name == "changed":
            source.write_text(source.read_text().replace("state += step", "state += step * 2"))
        output = evidence / name
        result = subprocess.run(
            [*cli, *extra, "--output", str(output)],
            text=True,
            capture_output=True,
            timeout=180,
            cwd=ROOT,
        )
        (evidence / f"{name}.stdout.log").write_text(result.stdout)
        (evidence / f"{name}.stderr.log").write_text(result.stderr)
        require(result.returncode == 0, f"{name}: {result.stderr}")
        rows = json.loads((output / "results.json").read_text())
        raw = json.loads((output / "jmh-results.json").read_text())
        require(len(rows) == len(raw) == 2)
        for original, row in zip(raw, rows, strict=True):
            require(all(row[k] == v for k, v in original.items()), "JMH values were modified")
            require(row["forks"] == 2 and len(row["primaryMetric"]["rawData"]) == 2)
            require(all(len(fork) == 3 for fork in row["primaryMetric"]["rawData"]))
            require(row["smh"]["execution"]["harness"] == "jmh")
        require(
            "Iterations" in result.stderr and "JMH 1.37" in result.stderr,
            "Visual JMH results missing",
        )
        if "--aot-cache" in extra:
            keys.append(rows[0]["smh"]["execution"]["aotCacheKey"])
            require(("Leyden cache hit" in result.stderr) == (name == "reused"))
    comparison = subprocess.run(
        [
            *launcher,
            "bench",
            "--execution",
            "hotspot",
            "--build-config",
            str(build),
            "--config",
            str(config),
            "--compare",
            str(evidence / "uncached/results.json"),
            "--display",
            "tui",
        ],
        text=True,
        capture_output=True,
        timeout=180,
        cwd=ROOT,
    )
    (evidence / "comparison.stdout.log").write_text(comparison.stdout)
    (evidence / "comparison.stderr.log").write_text(comparison.stderr)
    require(
        comparison.returncode == 0 and "Baseline · JMH · hotspot" in comparison.stderr,
        f"Cross-harness display failed: {comparison.stderr}",
    )
    require(keys[0] == keys[1] != keys[2], "AOT cache identity failed to track source content")
    # A failed benchmark must not leave a successful adapted report.
    source.write_text(
        source.read_text().replace(
            "return state += step * 2;", 'throw new IllegalStateException("fixture failure");'
        )
    )
    result = subprocess.run(
        [*cli, "--output", str(evidence / "failed")],
        text=True,
        capture_output=True,
        timeout=180,
        cwd=ROOT,
    )
    require(result.returncode != 0 and not (evidence / "failed/results.json").exists())
    (evidence / "failure.stderr.log").write_text(result.stderr)
    (evidence / "verification.json").write_text(
        json.dumps(
            dict(
                evidence="Real JMH 1.37, two parameter cases, two forks, three iterations; tiered/C2 and Leyden training/reuse/source invalidation; failure propagation",
                limits="Harness and startup/steady-state equivalence not established; no runtime speed ranking",
                artifact_identity=artifact_identity(
                    ROOT,
                    [
                        sdk / "smh.jar",
                        sdk / "smh-jmh.jar",
                        java,
                        *([args.cli.resolve()] if args.cli else []),
                    ],
                ),
            ),
            indent=2,
        )
        + "\n"
    )
    print(f"JMH/Leyden checks passed: {evidence}")


if __name__ == "__main__":
    main()
