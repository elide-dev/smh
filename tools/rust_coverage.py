#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Run isolated stable-line or nightly-branch Rust coverage with portable argument handling."""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--branch", action="store_true")
    args = parser.parse_args()
    mise = tomllib.loads((ROOT / "mise.toml").read_text())
    dependencies = tomllib.loads((ROOT / "tools/dependencies.toml").read_text())
    toolchain = dependencies["rust_coverage"]["nightly"] if args.branch else mise["tools"]["rust"]
    profile = "nightly" if args.branch else "stable"
    env = dict(os.environ, CARGO_LLVM_COV_TARGET_DIR=str(ROOT / "target" / ("coverage-" + profile)))
    reports = ROOT / "build/reports"
    reports.mkdir(parents=True, exist_ok=True)
    data = reports / ("rust-" + profile + ".json")
    summary = reports / ("rust-" + ("branch" if args.branch else "line") + "-summary.json")
    lcov = reports / ("rust-branch.lcov" if args.branch else "rust.lcov")
    identity_path = reports / ("rust-" + profile + "-identity.json")
    exported_junit = reports / ("rust-" + profile + ".junit.xml")
    for previous in (data, summary, lcov, identity_path, exported_junit):
        previous.unlink(missing_ok=True)
    commands = []

    def run(argv):
        commands.append(list(map(str, argv)))
        subprocess.run(argv, cwd=ROOT, env=env, check=True)

    if args.branch:
        run(
            [
                "rustup",
                "toolchain",
                "install",
                toolchain,
                "--profile",
                "minimal",
                "--component",
                "llvm-tools-preview",
            ]
        )
    else:
        run(["rustup", "component", "add", "llvm-tools-preview", "--toolchain", toolchain])
    cargo = ["cargo", "+" + toolchain, "llvm-cov"]
    common = ["--include-build-script", "--ignore-filename-regex", r"[/\\](tests|abi)[/\\]"]
    if args.branch:
        common.append("--branch")
    test_profile = "coverage-" + profile
    # Nextest's store directory is workspace-relative, independent of Cargo's target directory.
    junit = ROOT / "target/nextest" / test_profile / "junit.xml"
    junit.unlink(missing_ok=True)
    try:
        run(
            [
                *cargo,
                "nextest",
                "--locked",
                *common,
                "--json",
                "--output-path",
                str(data),
                "--profile",
                test_profile,
            ]
        )
    finally:
        if junit.is_file():
            shutil.copy2(junit, exported_junit)
    if not junit.is_file():
        raise ValueError("Nextest did not produce the required JUnit report")
    gate = [sys.executable, str(ROOT / "tools/rust_coverage_gate.py"), str(data)]
    if args.branch:
        gate.append("--branch-only")
    run([*gate, "--output", str(summary)])
    run([*cargo, "report", *common, "--lcov", "--output-path", str(lcov)])
    identity = {
        "toolchain": toolchain,
        "rustc": subprocess.check_output(
            ["rustc", "+" + toolchain, "--version", "--verbose"], text=True, env=env
        ),
        "coverageTool": subprocess.check_output([*cargo, "--version"], text=True, env=env),
        "testRunner": subprocess.check_output(
            ["cargo", "nextest", "--version"], text=True, env=env
        ),
        "commands": commands,
        "scope": "Production Rust plus build.rs; compiler-defined counters; source inventory checked independently",
    }
    identity_path.write_text(json.dumps(identity, indent=2) + "\n")


if __name__ == "__main__":
    main()
