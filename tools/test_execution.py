#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Real runtime-loaded execution, JIT installation evidence, and bytecode build timings."""

import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from build import ROOT
from verification import artifact_identity, require


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    java_home = args.java_home or (
        Path(os.environ["JAVA_HOME"])
        if os.environ.get("JAVA_HOME")
        else Path(shutil.which("java")).resolve().parent.parent
    )
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    root = ROOT / "build/execution-tests"
    root.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix="run-", dir=root))
    source = evidence / "ExecutionWork.java"
    template = """package execution; import org.openjdk.jmh.annotations.*;
@State(Scope.Thread) public class ExecutionWork {
  private int state;
  @Benchmark public int work() { state += STEP; return state; }
}
"""
    source.write_text(template.replace("STEP", "1"))
    settings = {
        "home": str(ROOT / "build"),
        "javaHome": str(java_home),
        "runtimeJavaHome": str(java_home),
        "sources": [str(source)],
        "output": str(evidence / "image"),
    }
    build = evidence / "build.json"
    build.write_text(json.dumps(settings))
    cli = [
        str(java),
        "-cp",
        os.pathsep.join([str(ROOT / "build/smh.jar"), str(ROOT / "build/lib/*")]),
        "dev.elide.smh.cli.Main",
    ]
    timings = []

    def invoke(name, command):
        start = time.perf_counter()
        with (
            (evidence / f"{name}.stdout.log").open("w") as out,
            (evidence / f"{name}.stderr.log").open("w") as err,
        ):
            result = subprocess.run(command, cwd=ROOT, stdout=out, stderr=err, timeout=180)
        elapsed = time.perf_counter() - start
        require(result.returncode == 0, f"{name} failed: {evidence}")
        return elapsed

    for case in ("cold", "unchanged", "changed-source"):
        if case == "changed-source":
            source.write_text(template.replace("STEP", "3"))
        elapsed = invoke(case, cli + ["build", "--config", str(build), "--bytecode-only"])
        log = (evidence / f"{case}.stderr.log").read_text()
        require(("Bytecode cache hit" in log) == (case == "unchanged"), log)
        timings.append(
            {
                "case": case,
                "seconds": elapsed,
                "scope": "fresh CLI process, identity checks and bytecode preparation",
            }
        )

    for backend, flags in (
        (
            "hotspot-c2",
            [
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+LogCompilation",
                f"-XX:LogFile={evidence / 'c2-compilation.xml'}",
            ],
        ),
        ("crema", ["-XX:+JITTraceCompilation", "-XX:+JITTraceCodeInstallations"]),
    ):
        settings.update(execution=backend, runtimeArgs=flags)
        build.write_text(json.dumps(settings))
        output = evidence / f"{backend}.json"
        run = evidence / f"{backend}-run.json"
        run.write_text(
            json.dumps(
                {
                    "output": str(output),
                    "warmupIterations": 5,
                    "warmupNanos": 200_000_000,
                    "measurementIterations": 3,
                    "measurementNanos": 100_000_000,
                }
            )
        )
        elapsed = invoke(
            backend,
            cli
            + ["bench", "--build-config", str(build), "--config", str(run), "--display", "plain"],
        )
        rows = json.loads(output.read_text())
        require(len(rows) == 1, rows)
        row = rows[0]
        require(row["smh"]["execution"]["backend"] == backend, row)
        require(row["smh"]["jitQuiescence"] == "unknown", row)
        require(row["primaryMetric"]["score"] > 0, row)
        logs = (evidence / f"{backend}.stdout.log").read_text() + (
            evidence / f"{backend}.stderr.log"
        ).read_text()
        if backend == "hotspot-c2":
            compiled = ET.parse(evidence / "c2-compilation.xml").iter("nmethod")
            require(
                any(
                    n.get("compiler") == "c2"
                    and n.get("method", "").startswith("execution.ExecutionWork work ")
                    for n in compiled
                ),
                f"No C2 nmethod installation for the benchmark: {evidence}",
            )
            token = "c2-compilation.xml: nmethod compiler=c2 method=execution.ExecutionWork work"
        else:
            token = "[Ristretto CodeMap] method=execution.ExecutionWork.work"
            require(
                token in logs, f"No benchmark JIT installation evidence for {backend}: {evidence}"
            )
        require("Bytecode cache hit" in logs, "Changing execution backend rebuilt bytecode")
        timings.append(
            {
                "case": backend,
                "seconds": elapsed,
                "scope": "fresh process including loading, warmup and measurement",
                "jitEvidence": token,
            }
        )
    (evidence / "evidence.json").write_text(
        json.dumps(
            {
                "identity": artifact_identity(
                    ROOT,
                    [
                        ROOT / "build/smh.jar",
                        ROOT / "build/smh-runtime.jar",
                        ROOT / "build/crema/smh-crema",
                    ],
                ),
                "timings": timings,
                "qualification": "functional execution and compilation evidence; not JIT quiescence or benchmark accuracy",
            },
            indent=2,
        )
        + "\n"
    )
    print(evidence)


if __name__ == "__main__":
    main()
