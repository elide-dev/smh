#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Build a nested Elide application, package it, then benchmark that JAR with SMH."""

import hashlib
import json
import os
import subprocess
from pathlib import Path

if __package__:
    from .build import ROOT, build
    from .verification import require
else:
    from build import ROOT, build
    from verification import require

APP = ROOT / "examples/invoice-app"


def logged(command, cwd, output):
    """Keep the exact command and separate stdout/stderr, including on failure."""
    output.parent.mkdir(parents=True, exist_ok=True)
    output.with_suffix(".command.json").write_text(json.dumps([str(x) for x in command]))
    with output.open("w") as log, output.with_suffix(".stderr.log").open("w") as err:
        result = subprocess.run(
            [str(x) for x in command], cwd=cwd, stdout=log, stderr=err, timeout=1200
        )
    require(result.returncode == 0, f"Command failed ({result.returncode}); see {output}")
    return output.read_text()


def build_and_test():
    # Packaging and native compilation deliberately run sequentially.
    build()
    evidence = ROOT / "build/invoice-tests"
    evidence.mkdir(parents=True, exist_ok=True)
    logged(["elide", "run", "assemble"], APP, evidence / "application-build.log")
    application = logged(
        ["elide", "java", "--", "-jar", "build/invoice-app.jar"],
        APP,
        evidence / "application-run.log",
    )
    require(application.strip() == "Total: 6497 cents", application)
    java = (
        Path(os.environ.get("JAVA_HOME", "/usr"))
        / "bin"
        / ("java.exe" if os.name == "nt" else "java")
    )
    cli = [
        str(java),
        "-cp",
        os.pathsep.join([str(ROOT / "build/smh.jar"), str(ROOT / "build/lib/*")]),
        "dev.elide.smh.cli.Main",
    ]
    logged([*cli, "build", "--config", "smh-build.json"], APP, evidence / "benchmark-build.log")
    binary = APP / "build/benchmarks" / ("benchmarks.exe" if os.name == "nt" else "benchmarks")
    output = logged(
        [*cli, "run", binary, "--config", "smh-run.json", "--display", "plain"],
        APP,
        evidence / "wall.json",
    )
    rows = json.loads(output)
    require(len(rows) == 2, "Both invoice sizes must be benchmarked")
    require({r["params"]["lines"] for r in rows} == {"10", "1000"})
    for row in rows:
        require(row["benchmark"] == "example.invoice.InvoiceBenchmark.total")
        require(row["vmName"] == "Substrate VM")
        require(row["primaryMetric"]["score"] > 0)
        require(len(row["primaryMetric"]["rawData"][0]) == 1000)
        require(row["smh"]["backend"] == "wall")
        require(len(row["smh"]["warmup"]["observations"]) == 100)
    jar_hash = hashlib.sha256((APP / "build/invoice-app.jar").read_bytes()).hexdigest()
    logged(["elide", "run", "assemble"], APP, evidence / "application-rebuild.log")
    require(
        hashlib.sha256((APP / "build/invoice-app.jar").read_bytes()).hexdigest() == jar_hash,
        "Unchanged application builds must produce identical JAR bytes",
    )
    cached_command = [*cli, "bench", "--display", "plain"]
    fresh = json.loads(logged([*cached_command, "--rebuild"], APP, evidence / "bench-first.json"))
    cached = json.loads(logged(cached_command, APP, evidence / "bench-hit.json"))
    require("Built ·" in (evidence / "bench-first.stderr.log").read_text())
    require("Cache hit ·" in (evidence / "bench-hit.stderr.log").read_text())
    for report in (fresh, cached):
        require(len(report) == 2)
        require(all(len(row["primaryMetric"]["rawData"][0]) == 1000 for row in report))
        require(all(len(row["smh"]["warmup"]["observations"]) == 100 for row in report))
    manifest = json.loads(
        logged([binary, "smh-fixed-work.json"], APP, evidence / "fixed-work.json")
    )
    require(len(manifest) == 1)
    require(manifest[0]["invocationsPerIteration"] == 100)
    require(manifest[0]["status"] == "pending-counter-import")
    (evidence / "identity.json").write_text(
        json.dumps(
            {
                "binarySha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                "applicationJarSha256": hashlib.sha256(
                    (APP / "build/invoice-app.jar").read_bytes()
                ).hexdigest(),
                "scope": "Elide application JAR -> SMH native benchmark -> wall and fixed-work execution",
            },
            indent=2,
        )
    )
    print("Nested Elide application and real native SMH benchmark passed:", evidence)
    return binary


if __name__ == "__main__":
    build_and_test()
