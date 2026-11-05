#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Elide compiles and resolves dependencies; this script assembles distributable pieces."""

import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def build():
    run("elide", "install", "--slim")
    cp = subprocess.check_output(["elide", "classpath"], text=True).strip().splitlines()[-1]
    lib = ROOT / "build/lib"
    lib.mkdir(parents=True, exist_ok=True)
    installed = set()
    for entry in cp.split(os.pathsep):
        path = Path(entry)
        if path.is_file() and path.suffix == ".jar":
            shutil.copy2(path, lib / path.name)
            installed.add(path.name)
    for path in lib.glob("*.jar"):
        if path.name not in installed:
            path.unlink()
    run("elide", "build", "compile-java-main")
    classes = ROOT / ".dev/jvm/classes/main/java"
    run(
        "elide",
        "jar",
        "--",
        "--create",
        "--date=2000-01-01T00:00:00Z",
        "--file",
        "build/smh.jar",
        "--main-class",
        "dev.elide.smh.cli.Main",
        "-C",
        classes,
        ".",
    )
    run(
        "elide",
        "jar",
        "--",
        "--create",
        "--date=2000-01-01T00:00:00Z",
        "--file",
        "build/smh-jmh.jar",
        "-C",
        classes,
        "dev/elide/smh/jmh",
    )
    # Stable runtime and API classes are the ONLY SMH code selected for the base layer.
    run(
        "elide",
        "jar",
        "--",
        "--create",
        "--date=2000-01-01T00:00:00Z",
        "--file",
        "build/smh-runtime.jar",
        "-C",
        classes,
        "dev/elide/smh/runtime",
    )


if __name__ == "__main__":
    build()
    if len(sys.argv) > 1 and sys.argv[1] == "crema":
        native_image = os.environ.get("SMH_NATIVE_IMAGE", "native-image")
        flags = [
            "--no-fallback",
            "-O2",
            "--parallelism=4",
            "-J-Xmx6g",
            "-H:+UnlockExperimentalVMOptions",
            "-H:+RuntimeClassLoading",
            "-H:+GraalJITCompileAtRuntime",
            "-H:Preserve=module=java.base",
            "-H:Preserve=package=dev.elide.smh.runtime",
            "-H:Preserve=package=org.openjdk.jmh.infra",
            "--initialize-at-build-time=org.openjdk.jmh.infra.Blackhole",
        ]
        runtime = [ROOT / "build/smh-runtime.jar"] + sorted(
            p
            for p in (ROOT / "build/lib").glob("*.jar")
            if p.name.startswith(("gson-", "jmh-core-", "nativeimage-", "word-"))
        )
        host_directory = ROOT / "build/crema"
        host_directory.mkdir(parents=True, exist_ok=True)
        executable = host_directory / ("smh-crema.exe" if os.name == "nt" else "smh-crema")
        Path(str(executable) + ".json").unlink(missing_ok=True)
        run(
            native_image,
            *flags,
            "-cp",
            os.pathsep.join(map(str, runtime)),
            "dev.elide.smh.runtime.BenchmarkHost",
            "-o",
            executable,
        )
        Path(str(executable) + ".json").write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "flags": flags,
                    "toolchain": subprocess.check_output([native_image, "--version"], text=True),
                    "sha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
                    "runtimeSha256": hashlib.sha256(runtime[0].read_bytes()).hexdigest(),
                    "files": {
                        p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in sorted(host_directory.iterdir())
                        if p.is_file()
                    },
                    "dependencies": {
                        p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in runtime
                    },
                },
                indent=2,
            )
            + "\n"
        )
    if len(sys.argv) > 1 and sys.argv[1] == "native":
        native = (
            ["cmd.exe", "/d", "/c", "native-image.cmd"] if os.name == "nt" else ["native-image"]
        )
        run(
            *native,
            "--no-fallback",
            "-O2",
            "--parallelism=4",
            "-J-Xmx4g",
            "-cp",
            os.pathsep.join(["build/smh.jar", "build/lib/*"]),
            "dev.elide.smh.cli.Main",
            "-o",
            "build/smh",
        )
        run("cargo", "build", "--release", "--locked")
        suffix = ".exe" if os.name == "nt" else ""
        shutil.copy2("target/release/smh-bootstrap" + suffix, "build/smh-bootstrap" + suffix)
