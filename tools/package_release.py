#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Package the portable SDK and Linux amd64 native tools with SHA-256 checksums."""

import hashlib
import subprocess
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    version = tomllib.loads((ROOT / "Cargo.toml").read_text())["package"]["version"]
    output = ROOT / "build/dist"
    output.mkdir(parents=True, exist_ok=True)
    common = [ROOT / name for name in ("LICENSE", "LICENSE-APACHE", "LICENSE-MIT", "README.md")]
    sdk = [ROOT / "build/smh.jar", ROOT / "build/smh-runtime.jar"]
    sdk += sorted((ROOT / "build/lib").glob("*.jar"))
    if len(sdk) <= 2:
        raise RuntimeError("SDK dependencies are missing")
    archives = []
    for kind, files in (
        ("sdk", sdk),
        ("linux-amd64", sdk + [ROOT / "build/smh", ROOT / "build/smh-bootstrap"]),
    ):
        archive = output / f"smh-{version}-{kind}.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
            for source in common + files:
                name = source.relative_to(ROOT / "build") if source in files else source.name
                bundle.write(source, str(name))
            bundle.writestr(
                "REVISION", subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT)
            )
        archives.append(archive)
    (output / "SHA256SUMS").write_text(
        "".join(
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in archives
        )
    )


if __name__ == "__main__":
    main()
