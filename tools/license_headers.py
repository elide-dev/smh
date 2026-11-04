#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Check SPDX headers for owned source/configuration; --fix adds missing headers."""

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = {".java", ".rs", ".py", ".pkl", ".toml", ".yml", ".yaml", ".md", ".seam"}
SKIP = {"CHANGELOG.md", ".git", ".dev", ".elide", "build", "target", "__pycache__", ".venv"}
COPYRIGHT = "SPDX-FileCopyrightText: 2026 SMH contributors"
LICENSE = "SPDX-License-Identifier: Apache-2.0 OR MIT"


def files(directory):
    for path in sorted(directory.iterdir()):
        if (
            (directory == ROOT and path.name in SKIP)
            or path.name in {"__pycache__", ".git", ".venv"}
            or path.is_symlink()
        ):
            continue
        if path.is_dir():
            yield from files(path)
        elif path.suffix in SUFFIXES or path.name == ".gitignore":
            yield path


def header(path):
    if path.suffix == ".md":
        return f"<!-- {COPYRIGHT} -->\n<!-- {LICENSE} -->\n"
    marker = "//" if path.suffix in {".java", ".rs", ".pkl"} else "#"
    return f"{marker} {COPYRIGHT}\n{marker} {LICENSE}\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fix", action="store_true")
    args = parser.parse_args()
    missing = []
    for path in files(ROOT):
        text = path.read_text(encoding="utf-8")
        body = text.split("\n", 1)[1] if text.startswith("#!") and "\n" in text else text
        if body.startswith(header(path)):
            continue
        if args.fix:
            lines = text.splitlines(keepends=True)
            start = lines.pop(0) if lines and lines[0].startswith("#!") else ""
            path.write_text(start + header(path) + "".join(lines), encoding="utf-8")
        else:
            missing.append(str(path.relative_to(ROOT)))
    if missing:
        print("Missing SPDX headers:\n" + "\n".join(missing), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
