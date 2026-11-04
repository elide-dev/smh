#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Pinned coverage-guided parser fuzz runs with retained corpora and broken controls."""

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import tomllib
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

from build import ROOT, build


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["callgrind", "run-config"], default="callgrind")
    parser.add_argument("--runs", type=int, default=10000)
    parser.add_argument("--seed", type=int, default=534948)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.runs < 1 or not 1 <= args.seed <= 2147483647:
        raise ValueError("Positive run count and a positive 31-bit seed are required")
    config_target = args.target == "run-config"
    target_class = (
        "dev.elide.smh.runtime.RunConfigFuzz"
        if config_target
        else "dev.elide.smh.cli.CallgrindFuzz"
    )
    instrument = (
        (
            "dev.elide.smh.runtime.RunConfig:dev.elide.smh.runtime.StabilityPolicy:"
            "dev.elide.smh.runtime.MeasurementBackend:dev.elide.smh.runtime.BatchCalibrationPolicy"
        )
        if config_target
        else "dev.elide.smh.cli.CallgrindDump"
    )
    source_name = "RunConfig.java" if config_target else "CallgrindDump.java"
    output = (
        args.output
        or ROOT
        / ("build/fuzz-" + args.target)
        / datetime.datetime.now(datetime.UTC).strftime("%Y%m%dT%H%M%S%fZ")
    ).resolve()
    output.mkdir(parents=True, exist_ok=False)
    dependency = tomllib.loads((ROOT / "tools/dependencies.toml").read_text())["jazzer"]
    jars = []
    for artifact, key in (("jazzer", "jar_sha256"), ("jazzer-api", "api_sha256")):
        jar = output / (artifact + ".jar")
        url = (
            f"https://repo.maven.apache.org/maven2/com/code-intelligence/{artifact}/"
            f"{dependency['version']}/{artifact}-{dependency['version']}.jar"
        )
        with urllib.request.urlopen(url, timeout=60) as response:
            jar.write_bytes(response.read())
        if digest(jar) != dependency[key]:
            raise ValueError("Jazzer artifact hash mismatch: " + artifact)
        jars.append(jar)
    build()
    runtime = output / "smh.jar"
    shutil.copy2(ROOT / "build/smh.jar", runtime)
    gson_sources = list((ROOT / "build/lib").glob("gson-*.jar"))
    if len(gson_sources) != 1:
        raise ValueError("Expected exactly one resolved Gson dependency")
    gson = output / gson_sources[0].name
    shutil.copy2(gson_sources[0], gson)
    classes = output / "classes"
    sources = sorted((ROOT / "tests/fuzz/java").rglob("*.java"))
    subprocess.run(
        [
            "javac",
            "-proc:none",
            "-cp",
            os.pathsep.join(map(str, [runtime, gson])),
            "-d",
            str(classes),
            *map(str, sources),
        ],
        cwd=ROOT,
        check=True,
    )
    artifacts = output / "artifacts"
    artifacts.mkdir()
    corpus = output / "corpus"
    shutil.copytree(ROOT / "tests/fuzz/corpus" / args.target, corpus)
    control_corpus = output / "control-corpus"
    control_corpus.mkdir()
    (control_corpus / "trigger").write_bytes(b"SMH")
    base = [
        "java",
        "-Xmx768m",
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+EnableDynamicAgentLoading",
        "-cp",
        os.pathsep.join(map(str, [*jars, classes, runtime, gson])),
        "com.code_intelligence.jazzer.Jazzer",
        "--reproducer_path=" + str(artifacts),
        "-artifact_prefix=" + str(artifacts) + os.sep,
        "-seed=" + str(args.seed),
        "-max_len=4096",
    ]
    if config_target:
        # include is intentionally a user-supplied regex, not interpolation into another pattern.
        base.append("--disabled_hooks=com.code_intelligence.jazzer.sanitizers.RegexInjection")
    commands = []

    def run(command, name, control=False):
        commands.append(command)
        (output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        with (output / (name + ".log")).open("w") as log:
            result = subprocess.run(
                command,
                cwd=ROOT,
                stdout=log,
                stderr=subprocess.STDOUT,
                timeout=max(600, args.runs // 100),
            )
        text = (output / (name + ".log")).read_text()
        if control:
            if result.returncode == 0 or "AssertionError: SMH_FUZZER_NEGATIVE_CONTROL" not in text:
                raise ValueError("Fuzzer did not detect the broken target")
        elif result.returncode != 0:
            raise ValueError(
                "Fuzzing found a failure; retain and minimize the saved input: " + str(artifacts)
            )
        return text

    control = [
        *base,
        "--target_class=dev.elide.smh.cli.FuzzerControl",
        "--instrumentation_includes=dev.elide.smh.cli.FuzzerControl",
        "-runs=100",
    ]
    run([*control, str(control_corpus)], "control", control=True)
    crashes = sorted(artifacts.glob("crash-*"))
    if not crashes:
        raise ValueError("Fuzzer did not retain a control reproducer")
    run([*control, str(crashes[0])], "control-replay", control=True)
    text = run(
        [
            *base,
            "--target_class=" + target_class,
            "--instrumentation_includes=" + instrument,
            "--coverage_report=" + str(output / "coverage.txt"),
            "-runs=" + str(args.runs),
            str(corpus),
        ],
        "fuzz",
    )
    completed = re.search(r"#(\d+)\s+DONE\s+cov:\s+(\d+)", text)
    if not completed or int(completed[1]) < args.runs or int(completed[2]) < 1:
        raise ValueError("Missing completed coverage-guided campaign evidence")
    coverage = (output / "coverage.txt").read_text()
    if not re.search(re.escape(source_name) + r": [1-9][0-9]*/[1-9][0-9]*", coverage):
        raise ValueError("Production parser instrumentation was not observed")
    junit = ET.Element(
        "testsuite", name=args.target + " fuzz campaign", tests="1", failures="0", errors="0"
    )
    ET.SubElement(junit, "testcase", name="coverage-guided-parser-and-value-oracle")
    ET.ElementTree(junit).write(output / "junit.xml", encoding="utf-8", xml_declaration=True)
    evidence = {
        "passed": True,
        "runs": int(completed[1]),
        "seed": args.seed,
        "coverageCounters": int(completed[2]),
        "expectedControlArtifacts": {path.name: digest(path) for path in crashes},
        "jazzer": dependency,
        "runtimeSha256": digest(runtime),
        "gsonSha256": digest(gson),
        "target": args.target,
        "java": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True),
        "sources": {str(source.relative_to(ROOT)): digest(source) for source in sources},
        "corpus": {path.name: digest(path) for path in sorted(corpus.iterdir())},
        "negativeControlReplayed": True,
        "scope": args.target
        + " parser and exact-value oracle; no CLI/state-graph/FFI fuzzing claim",
    }
    (output / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(output)


if __name__ == "__main__":
    main()
