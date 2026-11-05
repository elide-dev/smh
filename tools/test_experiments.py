#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Real startup/soak evidence. Run after SDK packaging, sequentially with native builds."""

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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--execution", choices=["hotspot", "hotspot-c2", "aot", "crema"], default="hotspot"
    )
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--valgrind", default="valgrind")
    parser.add_argument(
        "--cli", type=Path, help="Use a compiled SMH CLI instead of its JVM launcher"
    )
    parser.add_argument("--simulation", action="store_true")
    parser.add_argument("--layers", action="store_true")
    args = parser.parse_args()
    args.java_home = args.java_home or (
        Path(os.environ["JAVA_HOME"])
        if os.environ.get("JAVA_HOME")
        else Path(shutil.which("java")).resolve().parent.parent
    )
    require(not args.simulation or args.execution == "aot", "Simulation requires AOT")
    root = ROOT / "build/experiment-tests"
    root.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix=args.execution + "-", dir=root))
    source = evidence / "ExperimentWork.java"
    source.write_text("""
package experiments;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=0)
@Measurement(iterations=1, time=20, timeUnit=TimeUnit.MILLISECONDS)
public class ExperimentWork {
  @Param({"0"}) public int cleanupWork;
  @Param({"false"}) public boolean fail;
  @Param({"0"}) public int cleanupDelay;
  private long state;
  @Setup(Level.Trial) public void start() throws Exception {
    state=System.nanoTime();
    dev.elide.smh.runtime.Startup.ready();
    if(cleanupDelay>0) Thread.sleep(cleanupDelay);
  }
  @Benchmark public long work() {
    for(int i=0;i<100;i++) state=state*1664525+1013904223;
    return state;
  }
  @TearDown(Level.Trial) public void stop() throws Exception {
    if(cleanupDelay>0) Thread.sleep(cleanupDelay);
    for(int i=0;i<cleanupWork;i++) state=state*1664525+1013904223;
    if(fail) throw new IllegalStateException("deliberate cleanup failure");
    System.err.println("cleanup-checksum="+state);
  }
}
""")
    settings = {
        "home": str(ROOT / "build"),
        "javaHome": str(args.java_home),
        "runtimeJavaHome": str(args.java_home),
        "execution": args.execution,
        "sources": [str(source)],
        "output": str(evidence / "image"),
        "optimization": "2",
        "layers": args.layers,
        "nativeArgs": ["--parallelism=4", "-J-Xmx4g"],
    }
    build = evidence / "build.json"
    build.write_text(json.dumps(settings))
    cli = [
        str(args.java_home / "bin/java"),
        "-cp",
        os.pathsep.join([str(ROOT / "build/smh.jar"), str(ROOT / "build/lib/*")]),
        "dev.elide.smh.cli.Main",
    ]

    if args.cli:
        cli = [str(args.cli.resolve())]

    def invoke(name, config, options=(), success=True):
        run = evidence / f"{name}.json"
        run.write_text(json.dumps(config))
        destination = evidence / name
        command = cli + [
            "bench",
            "--build-config",
            str(build),
            "--config",
            str(run),
            "--output",
            str(destination),
            "--display",
            "tui",
            "--valgrind",
            args.valgrind,
            *options,
        ]
        (evidence / f"{name}.command.json").write_text(json.dumps(command))
        with (
            (evidence / f"{name}.stdout.json").open("w") as out,
            (evidence / f"{name}.stderr.ansi").open("w") as err,
        ):
            result = subprocess.run(
                command,
                stdout=out,
                stderr=err,
                cwd=ROOT,
                env=dict(os.environ, COLUMNS="140"),
                timeout=600,
            )
        require(
            (result.returncode == 0) == success,
            f"{name} unexpected exit {result.returncode}: {evidence}",
        )
        if not success:
            return destination
        document = json.loads((evidence / f"{name}.stdout.json").read_text())
        visual = (evidence / f"{name}.stderr.ansi").read_text()
        require("distribution" in visual and "\033[" in visual, "Missing colored histogram")
        return document, destination

    backend = "callgrind" if args.simulation else "wall"
    for endpoint in ["ready", "first-result", "exit"]:
        config = {
            "mode": "startup",
            "backend": backend,
            "until": endpoint,
            "launches": 3,
            "timeoutNanos": 120_000_000_000,
        }
        rows, destination = invoke("startup-" + endpoint, config)
        require(len(rows) == 1, "Expected one startup case")
        row = rows[0]
        observations = row["smh"]["observations"]
        require(len(observations) == 3, "Must retain every launch")
        require(all(o["score"] > 0 for o in observations), "Missing startup observations")
        pids = [o["processId"] for o in observations]
        require(
            all(pid > 0 for pid in pids) and len(set(pids)) == 3,
            "Each observation needs a fresh process",
        )
        if args.simulation:
            require(
                all("endpointNanos" not in o for o in observations),
                "Simulator must not report wall time",
            )
            require(
                all(len(o["rawFiles"]) == (1 if endpoint == "exit" else 2) for o in observations),
                "Missing endpoint/cleanup snapshots",
            )
        else:
            require(
                all(o["completionNanos"] > 0 for o in observations), "Missing process completion"
            )
    if not args.simulation:
        rows, _ = invoke(
            "cleanup-boundary",
            {
                "mode": "startup",
                "until": "first-result",
                "launches": 1,
                "params": {"cleanupDelay": ["250"]},
            },
        )
        observation = rows[0]["smh"]["observations"][0]
        require(
            observation["completionNanos"] - observation["endpointNanos"] >= 200_000_000,
            "Cleanup sleep leaked into the startup endpoint boundary",
        )
    if not args.simulation:
        rows, _ = invoke(
            "ready-boundary",
            {
                "mode": "startup",
                "until": "ready",
                "launches": 1,
                "params": {"cleanupDelay": ["250"]},
            },
        )
        observation = rows[0]["smh"]["observations"][0]
        require(
            observation["completionNanos"] - observation["endpointNanos"] >= 400_000_000,
            "Explicit readiness must precede the remainder of setup and cleanup",
        )
    soak = {"mode": "soak", "backend": backend, "warmupIterations": 0}
    if args.simulation:
        soak.update(operations=250, invocationsPerIteration=100)
    else:
        soak.update(durationNanos=200_000_000, measurementNanos=20_000_000)
    document, destination = invoke("soak", soak)
    if args.simulation:
        row = document["results"][0]
        require(
            [int(o["operations"]) for o in row["observations"]] == [100, 100, 50],
            "Partial final window lost",
        )
        require(row["primaryMetric"]["denominator"] == "250", "Incorrect work normalization")
    else:
        row = document[0]
        phase = row["smh"]["measurement"]
        require(phase["stoppingReason"] == "duration-reached", "Soak stopped early")
        require(phase["observations"][-1]["endNanos"] >= 200_000_000, "Budget not reached")
        journal = destination / "results.json.progress.jsonl"
        require(
            len(journal.read_text().splitlines()) == len(phase["observations"]),
            "Window journal incomplete",
        )
    failed = invoke(
        "cleanup-failure",
        {
            "mode": "startup",
            "backend": backend,
            "until": "ready",
            "launches": 1,
            "timeoutNanos": 120_000_000_000,
            "params": {"fail": ["true"]},
        },
        success=False,
    )
    require(not (failed / "results.json").exists(), "Failed cleanup published a result")
    require(list(failed.glob("case-*/launch-*/failure.json")), "Failure evidence missing")
    (evidence / "evidence.json").write_text(
        json.dumps(
            {
                **artifact_identity(
                    ROOT,
                    [
                        source,
                        ROOT / "build/smh.jar",
                        ROOT / "build/smh-runtime.jar",
                        *([args.cli.resolve()] if args.cli else []),
                    ],
                ),
                "execution": args.execution,
                "backend": backend,
                "layers": args.layers,
                "checks": [
                    "fresh-processes",
                    "startup-endpoints",
                    "full-soak-budget",
                    "retained-windows",
                    "histograms",
                    "cleanup-failure",
                    "explicit-readiness",
                ],
            },
            indent=2,
        )
    )
    print(evidence)


if __name__ == "__main__":
    main()
