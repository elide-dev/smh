#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Functional checks: Elide annotation processing, generated execution, CLI and layer variants."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

from verification import require

from build import ROOT, build, run

os.chdir(ROOT)
build()
lib = sorted((ROOT / "build/lib").glob("*.jar"))
cp = os.pathsep.join(map(str, [ROOT / "build/smh.jar", *lib]))
dest = ROOT / "build/test"
dest.mkdir(parents=True, exist_ok=True)
processor = "dev.elide.smh.processor.BenchmarkProcessor"
run(
    "elide",
    "javac",
    "--",
    "--release",
    "25",
    "-cp",
    cp,
    "-processorpath",
    cp,
    "-processor",
    processor,
    "-Asmh.observer=dev.elide.smh.fixtures.CountingObserver",
    "-d",
    dest,
    *sorted(ROOT.glob("tests/fixtures/**/*.java")),
    *sorted(ROOT.glob("tests/java/**/*.java")),
)
run("java", "-cp", str(dest) + os.pathsep + cp, "dev.elide.smh.HarnessTest", dest / "results.json")
cli = ["java", "-cp", cp, "dev.elide.smh.cli.Main"]
help_output = subprocess.check_output([*cli, "--help"], text=True)
require("SubstrateVM" in help_output and "build" in help_output)
require(help_output.startswith("Usage: smh"), "Diagnostic logs must not contaminate stdout")

for annotation, message in [
    ("@Threads(2)", "single-thread"),
    ("@BenchmarkMode(Mode.SampleTime)", "Unsupported mode"),
    ('@Group("group")', "Unsupported JMH annotation"),
]:
    source = dest / "Invalid.java"
    source.write_text(
        "import org.openjdk.jmh.annotations.*; public class Invalid { "
        + annotation
        + " @Benchmark public void bad() {} }"
    )
    result = subprocess.run(
        [
            "elide",
            "javac",
            "--",
            "-cp",
            cp,
            "-processorpath",
            cp,
            "-processor",
            processor,
            "-d",
            str(dest / "invalid"),
            str(source),
        ],
        text=True,
        capture_output=True,
    )
    require(result.returncode != 0 and message in result.stderr, result.stderr)

config = json.loads((ROOT / "examples/build.json").read_text())
compiler = dest / "native-image-version"
compiler.write_text("print('native-image functional-test-toolchain')\n")
config.update(
    home=str(ROOT / "build"),
    sources=[str(ROOT / "examples/src")],
    output=str(dest / "dry-run"),
    nativeImage=[sys.executable, str(compiler)],
)
build_file = dest / "build.json"


def layer_key():
    build_file.write_text(json.dumps(config))
    result = subprocess.run(
        [*cli, "build", "--config", str(build_file), "--dry-run"],
        text=True,
        capture_output=True,
        check=True,
    )
    executable = "benchmarks.exe" if os.name == "nt" else "benchmarks"
    require(result.stdout.strip() == str(Path(config["output"]) / executable), result.stdout)
    return re.search(
        r"-H:LayerUse=.+[/\\]layers[/\\]([0-9a-f]+)[/\\]smh-base.nil", result.stderr
    ).group(1), result.stderr


keys = []
for level in ("1", "2", "3"):
    config["optimization"] = level
    key, command = layer_key()
    require(command.count("-O" + level) in {1, 2})  # cached base need not rebuild
    keys.append(key)
require(len(set(keys)) == 3, "Optimization variants must not share base layers")
config["pgo"] = "instrument"
instrument, command = layer_key()
require("--pgo-instrument" in command and instrument not in keys)
profile = dest / "test.iprof"
profile.write_text("profile-a")
config.update(pgo="use", profile=str(profile))
first, command = layer_key()
require("--pgo=" in command)
profile.write_text("profile-b")
second, _ = layer_key()
require(first != second, "Changed profile content must invalidate base layers")
run("cargo", "test", "--locked")
print("All functional checks passed")
