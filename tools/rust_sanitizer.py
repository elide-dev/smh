#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Instrument Rust bootstrap and test providers; require a real FFI overflow detection first."""

import argparse
import datetime
import hashlib
import json
import os
import platform
import shutil
import subprocess
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify_result(returncode, output, expected_diagnostic=None):
    """A crash alone is not proof that the intended instrumentor worked."""
    if expected_diagnostic:
        if returncode != 86 or expected_diagnostic not in output:
            raise ValueError("Sanitizer did not detect the expected violation")
    elif returncode != 0:
        raise ValueError("Instrumented clean command failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if platform.system() != "Linux":
        raise ValueError("This sanitizer profile currently qualifies Linux workers only")
    toolchain = tomllib.loads((ROOT / "tools/dependencies.toml").read_text())["rust_sanitizer"][
        "nightly"
    ]
    subprocess.run(
        ["rustup", "toolchain", "install", toolchain, "--profile", "minimal"], check=True
    )
    rustc = subprocess.check_output(
        ["rustup", "which", "--toolchain", toolchain, "rustc"], text=True
    ).strip()
    identity = subprocess.check_output([rustc, "--version", "--verbose"], text=True)
    target = next(
        line.removeprefix("host: ") for line in identity.splitlines() if line.startswith("host: ")
    )
    if target not in {"x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu"}:
        raise ValueError("Unsupported sanitizer host: " + target)
    output = (
        args.output
        or ROOT
        / "build/rust-asan"
        / datetime.datetime.now(datetime.UTC).strftime("%Y%m%dT%H%M%S%fZ")
    ).resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = dict(
        os.environ,
        RUSTUP_TOOLCHAIN=toolchain,
        RUSTC=rustc,
        RUSTFLAGS="-Zsanitizer=address -Cforce-frame-pointers=yes -Clink-arg=-Wl,--export-dynamic",
        CARGO_TARGET_DIR=str(ROOT / "target/sanitizer-address"),
        SMH_TEST_RUSTC=rustc,
        SMH_TEST_SANITIZER="address",
        ASAN_OPTIONS="detect_leaks=1:halt_on_error=1:exitcode=86",
        LSAN_OPTIONS="exitcode=86",
    )
    env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    commands = []

    def run(command, name, expected_diagnostic=None):
        commands.append(list(map(str, command)))
        (output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        with (output / (name + ".log")).open("w") as log:
            result = subprocess.run(
                command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=600
            )
        contents = (output / (name + ".log")).read_text()
        try:
            verify_result(result.returncode, contents, expected_diagnostic)
        except ValueError as error:
            raise ValueError(name + ": " + str(error)) from error

    cargo = ["cargo", "+" + toolchain]
    run([*cargo, "build", "--locked", "--target", target], "build")
    bootstrap = Path(env["CARGO_TARGET_DIR"]) / target / "debug/smh-bootstrap"
    run([str(bootstrap), "--version"], "clean-startup")
    provider_env = env.copy()
    env["SMH_TEST_ABI_RS"] = str(ROOT / "abi/generated" / target / "seam.rs")
    controls = []
    for variant, diagnostic in (
        ("asan_overflow", "AddressSanitizer: heap-buffer-overflow"),
        ("asan_use_after_free", "AddressSanitizer: heap-use-after-free"),
        ("asan_leak", "LeakSanitizer: detected memory leaks"),
    ):
        provider = output / ("lib" + variant + ".so")
        run(
            [
                rustc,
                "--crate-type=cdylib",
                "--edition=2024",
                "-Cpanic=abort",
                "-Clinker=gcc",
                "-Zsanitizer=address",
                "-Cforce-frame-pointers=yes",
                "--cfg",
                variant,
                str(ROOT / "tests/ffi/provider.rs"),
                "-o",
                str(provider),
            ],
            "build-" + variant,
        )
        run([str(bootstrap), str(provider)], variant, diagnostic)
        controls.append(
            {
                "variant": variant,
                "expectedDiagnostic": diagnostic,
                "providerSha256": hashlib.sha256(provider.read_bytes()).hexdigest(),
                "exitCode": 86,
            }
        )
    env = provider_env
    junit = ROOT / "target/nextest/sanitizer-address/junit.xml"
    junit.unlink(missing_ok=True)
    try:
        run(
            [
                *cargo,
                "nextest",
                "run",
                "--locked",
                "--target",
                target,
                "--profile",
                "sanitizer-address",
            ],
            "tests",
        )
    finally:
        if junit.is_file():
            shutil.copy2(junit, output / "junit.xml")
    if not junit.is_file():
        raise ValueError("Missing sanitizer JUnit report")
    suite = ET.parse(junit).getroot()
    if len(suite.findall(".//testcase")) < 11 or any(
        int(suite.get(key, "-1")) != 0 for key in ("failures", "errors", "skipped")
    ):
        raise ValueError("Incomplete or failing sanitizer test inventory")
    evidence = {
        "passed": True,
        "rustc": identity,
        "target": target,
        "commands": commands,
        "options": env["ASAN_OPTIONS"],
        "rustflags": env["RUSTFLAGS"],
        "rustcExecutable": rustc,
        "negativeControls": controls,
        "leakOptions": env["LSAN_OPTIONS"],
        "bootstrapSha256": hashlib.sha256(bootstrap.read_bytes()).hexdigest(),
        "scope": "Rust bootstrap, libloading, test harness and Rust test providers; standard library is not rebuilt; no SVM heap or JIT instrumentation claim",
    }
    (output / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(output)


if __name__ == "__main__":
    main()
