#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Build with Elide once; exercise portable JVM behavior on native six-platform workers."""

import argparse
import hashlib
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def prepare(bundle):
    subprocess.run(["elide", "test", "--include-tag=portable"], cwd=ROOT, check=True)
    if bundle.exists():
        shutil.rmtree(bundle)
    bundle.mkdir(parents=True)
    for name in ["main", "test"]:
        shutil.copytree(ROOT / f".dev/jvm/classes/{name}/java", bundle / name)
    library = bundle / "lib"
    library.mkdir()
    for jar in sorted((ROOT / ".dev/dependencies/m2").rglob("*.jar")):
        if jar.name.endswith(("-sources.jar", "-javadoc.jar")):
            continue
        destination = library / jar.name
        if destination.exists() and destination.read_bytes() != jar.read_bytes():
            raise ValueError(f"Conflicting dependency filenames: {jar.name}")
        shutil.copy2(jar, destination)
    if not list(library.glob("junit-platform-console-standalone-*.jar")):
        raise ValueError("The portable test launcher dependency was not installed")
    manifest = []
    for file in sorted(bundle.rglob("*")):
        if file.is_file():
            manifest.append(
                f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.relative_to(bundle).as_posix()}"
            )
    (bundle / "SHA256SUMS").write_text("\n".join(manifest) + "\n", encoding="utf-8")


def execute(bundle):
    listed = set()
    for line in (bundle / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        expected, relative = line.split("  ", 1)
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("Invalid bundle manifest path")
        if relative in listed:
            raise ValueError("Duplicate bundle manifest entry")
        listed.add(relative)
        if hashlib.sha256((bundle / path).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Portable bundle digest mismatch: {relative}")
    actual = set()
    for path in bundle.rglob("*"):
        if path.is_symlink():
            raise ValueError("Portable bundles cannot contain symbolic links")
        if path.is_file() and path.name != "SHA256SUMS":
            actual.add(path.relative_to(bundle).as_posix())
    if listed != actual:
        raise ValueError("Portable bundle inventory does not match its manifest")
    classpath = os.pathsep.join(
        map(str, [bundle / "main", bundle / "test", *sorted((bundle / "lib").glob("*.jar"))])
    )
    subprocess.run(
        [
            "java",
            "-cp",
            classpath,
            "org.junit.platform.console.ConsoleLauncher",
            "execute",
            "--disable-banner",
            "--include-tag=portable",
            "--fail-if-no-tests",
            f"--scan-class-path={bundle / 'test'}",
            "--reports-dir",
            str(ROOT / "build/reports/portable"),
        ],
        check=True,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "run"])
    parser.add_argument("--bundle", type=Path, default=ROOT / "build/portable")
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    if args.action == "prepare":
        prepare(bundle)
    else:
        execute(bundle)


if __name__ == "__main__":
    main()
