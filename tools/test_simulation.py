#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Real Callgrind evidence: repeatability, exact work, fixture exclusion, layers, broken borders."""

import argparse
import datetime
import json
import os
import shutil
import subprocess
from pathlib import Path

from qualify_compiler import sha256, tool_identity

from build import ROOT, build
from verification import require


def execute(command, log):
    with log.open("w") as output:
        subprocess.run(
            list(map(str, command)), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, check=True
        )


def qualify(output):
    output.mkdir(parents=True, exist_ok=False)
    build()
    snapshot = output / "snapshot"
    snapshot.mkdir()
    for name in ("smh.jar", "smh-runtime.jar"):
        shutil.copy2(ROOT / "build" / name, snapshot / name)
    shutil.copytree(ROOT / "build/lib", snapshot / "lib")
    cp = os.pathsep.join([str(snapshot / "smh.jar"), str(snapshot / "lib/*")])
    cli = ["java", "-cp", cp, "dev.elide.smh.cli.Main"]
    source = ROOT / "tests/qualification/dev/elide/smh/proof/SimulationCases.java"
    importer = ROOT / "tests/qualification/dev/elide/smh/proof/SimulationImport.java"
    evidence = []
    for layered in (False, True):
        variant = output / ("layered" if layered else "monolithic")
        variant.mkdir()
        configuration = {
            "home": str(snapshot),
            "sources": [str(source)],
            "output": str(variant / "image"),
            "optimization": "2",
            "layers": layered,
            "nativeArgs": [
                "--parallelism=4",
                "-J-Xmx4g",
                "-g",
                "-H:-DeleteLocalSymbols",
                "-march=compatibility",
            ],
        }
        build_config = variant / "build.json"
        build_config.write_text(json.dumps(configuration, indent=2) + "\n")
        execute([*cli, "build", "--config", build_config], variant / "build.log")
        binary = variant / "image/benchmarks"
        counts = {}
        for name, budget, warmups, fixture_work in (
            ("baseline", 100, 1, 0),
            ("repeat", 100, 1, 0),
            ("warmup", 100, 10, 0),
            ("fixtures", 100, 1, 50000),
            ("double", 200, 1, 0),
            ("triple", 300, 1, 0),
        ):
            run_config = variant / (name + ".json")
            run_config.write_text(
                json.dumps(
                    {
                        "backend": "callgrind",
                        "invocationsPerIteration": budget,
                        "warmupIterations": warmups,
                        "measurementIterations": 3,
                        "params": {"fixtureWork": [str(fixture_work)]},
                    },
                    indent=2,
                )
                + "\n"
            )
            destination = variant / name
            execute(
                [*cli, "simulate", binary, "--config", run_config, "--output", destination],
                variant / (name + ".log"),
            )
            report = json.loads((destination / "results.json").read_text())
            rows = report["results"]
            require(len(rows) == 2, "Simulation must preserve both parameter cases")
            counts[name] = {}
            for row in rows:
                raw = [int(sample["events"]["Ir"]) for sample in row["observations"]]
                require(
                    len(raw) == 3 and len(set(raw)) == 1,
                    "Instruction counts must repeat in this deterministic fixture",
                )
                require(
                    row["primaryMetric"]["scoreUnit"] == "instructions/op",
                    "Simulation must not claim time units",
                )
                require(int(row["operationsPerIteration"]) == budget, "Work budget mismatch")
                counts[name][row["params"]["size"]] = raw[0]
        for name in ("repeat", "warmup", "fixtures"):
            require(
                counts[name] == counts["baseline"],
                "Warmup/fixtures/repetition changed measured instruction counts: " + name,
            )
        for size, base in counts["baseline"].items():
            delta = counts["double"][size] - base
            require(
                delta > 0 and counts["triple"][size] - counts["double"][size] == delta,
                "Fixed-work instruction counts must follow an affine relation",
            )
        controls = []
        original = json.loads((variant / "baseline/command.json").read_text())
        for name in ("collection-disabled", "collection-unbounded"):
            destination = variant / name
            destination.mkdir()
            command = [arg for arg in original if not arg.startswith("--toggle-collect=")]
            command = [
                arg.replace("--collect-atstart=no", "--collect-atstart=yes")
                if name == "collection-unbounded"
                else arg
                for arg in command
            ]
            command = [
                "--callgrind-out-file=" + str(destination / "callgrind.out.%p")
                if arg.startswith("--callgrind-out-file=")
                else arg
                for arg in command
            ]
            config = json.loads(Path(command[-1]).read_text())
            config["output"] = str(destination / "execution.json")
            control_config = destination / "run.json"
            control_config.write_text(json.dumps(config))
            command[-1] = str(control_config)
            with (
                (destination / "benchmark.stdout.log").open("w") as stdout,
                (destination / "valgrind.log").open("w") as stderr,
            ):
                subprocess.run(command, cwd=ROOT, stdout=stdout, stderr=stderr, check=True)
            result = subprocess.run(
                ["java", "-cp", cp, str(importer), str(destination)],
                cwd=ROOT,
                text=True,
                capture_output=True,
            )
            (destination / "import.log").write_text(result.stdout + result.stderr)
            require(
                result.returncode != 0 and "IllegalArgumentException" in result.stderr,
                "Broken border was not rejected by the production importer: " + name,
            )
            controls.append({"name": name, "command": command, "rejected": True})
        evidence.append(
            {
                "variant": variant.name,
                "counts": counts,
                "controls": controls,
                "binarySha256": sha256(binary),
            }
        )
    result = {
        "scope": "Concrete Linux Callgrind region fixtures; general native accuracy remains unqualified",
        "tools": {name: tool_identity(name) for name in ("native-image", "java", "valgrind")},
        "sources": {str(path.relative_to(ROOT)): sha256(path) for path in (source, importer)},
        "variants": evidence,
        "artifacts": {
            str(path.relative_to(output)): sha256(path)
            for path in sorted(output.rglob("*"))
            if path.is_file() and "snapshot" not in path.relative_to(output).parts
        },
    }
    (output / "evidence.json").write_text(json.dumps(result, indent=2) + "\n")
    print("Real simulation fixtures and broken-border controls passed:", output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or ROOT / "build/simulation-tests" / datetime.datetime.now(
        datetime.UTC
    ).strftime("%Y%m%dT%H%M%S%fZ")
    qualify(output.resolve())


if __name__ == "__main__":
    main()
