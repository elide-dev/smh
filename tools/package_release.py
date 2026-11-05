#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Package the portable SDK and Linux amd64 native tools with SHA-256 checksums."""

import hashlib
import json
import re
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
    sdk += [ROOT / "build/smh-jmh.jar"]
    sdk += sorted((ROOT / "build/lib").glob("*.jar"))
    if len(sdk) <= 3:
        raise RuntimeError("SDK dependencies are missing")
    layers = []
    for directory in sorted((ROOT / "build/layers").glob("*")):
        if not re.fullmatch(r"[0-9a-f]{64}", directory.name):
            continue
        archive, checksum = directory / "smh-base.nil", directory / "SHA256"
        if not checksum.is_file():
            continue
        if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum.read_text():
            raise RuntimeError(f"Invalid layer checksum: {archive}")
        layers.extend([archive, checksum])
    if not layers:
        raise RuntimeError("Prepare release layers with smh build --base-only before packaging")
    host_directory = ROOT / "build/crema"
    host_manifest = host_directory / "smh-crema.json"
    host = json.loads(host_manifest.read_text())
    if host["sha256"] != hashlib.sha256((host_directory / "smh-crema").read_bytes()).hexdigest():
        raise RuntimeError("Crema host digest does not match its manifest")
    if host["runtimeSha256"] != hashlib.sha256(sdk[1].read_bytes()).hexdigest():
        raise RuntimeError("Crema host SDK mismatch")
    host_files = [host_manifest]
    if "smh-crema" not in host["files"]:
        raise RuntimeError("Crema native inventory is incomplete")
    for name, digest in host["files"].items():
        path = host_directory / name
        if path.parent != host_directory or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise RuntimeError(f"Crema native file mismatch: {name}")
        host_files.append(path)
    archives = []
    for kind, files in (
        ("sdk", sdk),
        ("linux-amd64", sdk + [ROOT / "build/smh", ROOT / "build/smh-bootstrap"]),
        ("linux-amd64-layers", layers),
        ("linux-amd64-crema", sdk + host_files),
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
