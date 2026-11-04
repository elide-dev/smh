#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Run isolated Miri lifecycle checks with strict provenance and both borrow models."""

import argparse
import datetime
import json
import os
import subprocess
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    toolchain = tomllib.loads((ROOT / "tools/dependencies.toml").read_text())["rust_miri"][
        "nightly"
    ]
    subprocess.run(
        [
            "rustup",
            "toolchain",
            "install",
            toolchain,
            "--profile",
            "minimal",
            "--component",
            "miri,rust-src",
        ],
        check=True,
    )
    identity = subprocess.check_output(["rustc", "+" + toolchain, "-vV"], text=True)
    target = args.target or next(
        line.removeprefix("host: ") for line in identity.splitlines() if line.startswith("host: ")
    )
    if not (ROOT / "abi/generated" / target / "seam.rs").is_file() or "/" in target:
        raise ValueError("Unsupported Miri ABI target: " + target)
    output = (
        args.output
        or ROOT / "build/miri" / datetime.datetime.now(datetime.UTC).strftime("%Y%m%dT%H%M%S%fZ")
    ).resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, CARGO_TARGET_DIR=str(ROOT / "target/miri-smh"))
    for key in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTC", "MIRI_SYSROOT"):
        env.pop(key, None)
    cargo = ["cargo", "+" + toolchain, "miri"]
    commands, runs = [], []
    junit = ET.Element("testsuites")

    def run(command, name, control=False):
        commands.append({"command": list(map(str, command)), "flags": env.get("MIRIFLAGS", "")})
        (output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        with (output / (name + ".log")).open("w") as log:
            result = subprocess.run(
                command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=600
            )
        text = (output / (name + ".log")).read_text()
        if control:
            if result.returncode == 0 or "Undefined Behavior" not in text or "freed" not in text:
                raise ValueError("Miri failed to detect the use-after-free control: " + name)
        elif result.returncode != 0:
            raise ValueError("Miri command failed: " + name)
        return text

    for model in ("stacked", "tree"):
        for seed in (0, 1):
            name = f"{model}-{seed}"
            env["MIRIFLAGS"] = f"-Zmiri-strict-provenance -Zmiri-seed={seed}"
            if model == "tree":
                env["MIRIFLAGS"] += " -Zmiri-tree-borrows"
            fixture = [
                *cargo,
                "run",
                "--locked",
                "--manifest-path",
                "tests/miri-control/Cargo.toml",
                "--target",
                target,
            ]
            run(fixture, name + "-clean-control")
            run([*fixture, "--", "use-after-free"], name + "-ub-control", control=True)
            text = run(
                [
                    *cargo,
                    "test",
                    "--locked",
                    "--bin",
                    "smh-bootstrap",
                    "--target",
                    target,
                    "--",
                    "-Zunstable-options",
                    "--format=json",
                ],
                name + "-tests",
            )
            events = [json.loads(line) for line in text.splitlines() if line.startswith("{")]
            summaries = [
                event
                for event in events
                if event.get("type") == "suite" and event.get("event") == "ok"
            ]
            expected = 6 if "windows" in target else 7
            if (
                len(summaries) != 1
                or summaries[0].get("passed") != expected
                or any(summaries[0].get(key) != 0 for key in ("failed", "ignored", "filtered_out"))
            ):
                raise ValueError("Incomplete Miri test inventory: " + name)
            cases = [
                event
                for event in events
                if event.get("type") == "test" and event.get("event") == "ok"
            ]
            if len(cases) != expected:
                raise ValueError("Missing Miri test events: " + name)
            suite = ET.SubElement(
                junit,
                "testsuite",
                name=name,
                tests=str(expected),
                failures="0",
                errors="0",
                skipped="0",
            )
            for case in cases:
                ET.SubElement(suite, "testcase", name=case["name"], classname="miri." + name)
            runs.append({"model": model, "seed": seed, "passed": expected})
    ET.ElementTree(junit).write(output / "junit.xml", encoding="utf-8", xml_declaration=True)
    (output / "evidence.json").write_text(
        json.dumps(
            {
                "passed": True,
                "rustc": identity,
                "target": target,
                "runs": runs,
                "scope": "Interpreted Rust lifecycle/parser tests with Rust callbacks; no dynamic loader, real native ABI, SVM heap, JIT or hardware execution claim",
            },
            indent=2,
        )
        + "\n"
    )
    print(output)


if __name__ == "__main__":
    main()
