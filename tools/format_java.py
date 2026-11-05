#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Check all owned Java using the formatter pinned in mise.toml."""

import argparse
import subprocess
import tomllib
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fix", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    sources = sorted(
        path
        for directory in (
            "src",
            "tests/java",
            "tests/fixtures",
            "tests/qualification",
            "tests/fuzz/java",
            "examples/src",
            "examples/invoice-app/src",
            "examples/invoice-app/benchmarks",
        )
        for path in (root / directory).rglob("*.java")
    )
    flags = ["--replace"] if args.fix else ["--dry-run", "--set-exit-if-changed"]
    # Elide also exports this executable name; select the separately pinned tool explicitly.
    version = tomllib.loads((root / "mise.toml").read_text())["tools"]["google-java-format"]
    formatter = subprocess.check_output(
        ["mise", "which", "google-java-format", f"--tool=google-java-format@{version}"],
        cwd=root,
        text=True,
    ).strip()
    return subprocess.run([formatter, *flags, *map(str, sources)], check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
