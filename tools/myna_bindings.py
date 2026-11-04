#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Build the pinned Myna generator and reproduce SMH's C/Rust/SVM ABI descriptions."""

import argparse
import hashlib
import json
import shutil
import subprocess
import tarfile
import tempfile
import tomllib
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGETS = (
    "x86_64-unknown-linux-gnu",
    "aarch64-unknown-linux-gnu",
    "x86_64-apple-darwin",
    "aarch64-apple-darwin",
    "x86_64-pc-windows-msvc",
    "aarch64-pc-windows-msvc",
)


def extend_windows_subset(source):
    """Myna 0.7 lacks these targets. Limit this adaptation to SMH's i32/i64/pointer seam."""
    validator = source / "dev/elide/myna/validate/Validator.java"
    text = validator.read_text()
    anchor = '"x86_64-unknown-linux-gnu");'
    if text.count(anchor) != 1:
        raise ValueError("Pinned Myna target table has changed")
    validator.write_text(
        text.replace(
            anchor,
            '"x86_64-unknown-linux-gnu", "x86_64-pc-windows-msvc", "aarch64-pc-windows-msvc");',
        )
    )
    types = source / "dev/elide/myna/backend/Types.java"
    text = types.read_text()
    anchor = 'if (target.equals("aarch64-unknown-linux-gnu")) return "";'
    if text.count(anchor) != 1:
        raise ValueError("Pinned Myna extension policy has changed")
    types.write_text(
        text.replace(
            anchor,
            'if (target.equals("aarch64-unknown-linux-gnu") || target.contains("-windows-")) return "";',
        )
    )


def generator():
    pin = tomllib.loads((ROOT / "tools/dependencies.toml").read_text())["myna"]
    home = ROOT / "build/tools" / ("myna-" + pin["revision"] + "-smh1")
    classes = home / "classes"
    marker = home / "inputs.json"
    expected = {
        "upstream": pin,
        "adapterSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }
    if marker.is_file() and json.loads(marker.read_text()) == expected and classes.is_dir():
        return classes
    home.mkdir(parents=True, exist_ok=True)
    archive = home / "source.tar.gz"
    if (
        not archive.is_file()
        or hashlib.sha256(archive.read_bytes()).hexdigest() != pin["archive_sha256"]
    ):
        url = "https://codeload.github.com/elide-dev/myna/tar.gz/" + pin["revision"]
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != pin["archive_sha256"]:
            raise ValueError("Myna source archive digest mismatch")
        archive.write_bytes(data)
    # Only the pinned, verified archive is extracted. Never modify a sibling Myna checkout.
    with tempfile.TemporaryDirectory(dir=home) as temporary:
        with tarfile.open(archive) as bundle:
            bundle.extractall(temporary, filter="data")
        source = Path(temporary) / ("myna-" + pin["revision"]) / "src/main/java"
        extend_windows_subset(source)
        if classes.exists():
            shutil.rmtree(classes)
        classes.mkdir()
        subprocess.run(
            [
                "elide",
                "javac",
                "--",
                "--release",
                "25",
                "-d",
                str(classes),
                *map(str, sorted(source.rglob("*.java"))),
            ],
            check=True,
            cwd=ROOT,
        )
    version = subprocess.check_output(
        ["java", "-cp", str(classes), "dev.elide.myna.cli.Main", "--version"], text=True
    ).strip()
    if version != pin["version"]:
        raise ValueError("Myna generator version mismatch")
    marker.write_text(json.dumps(expected, indent=2) + "\n")
    return classes


def validate_description(description):
    if description["structs"] or description["callbacks"] or description["opaqueTypes"]:
        raise ValueError("SMH's Windows adaptation does not qualify aggregate/callback ABIs")
    for function in description["functions"]:
        if function["returnType"] not in {"i32", "i64"} or function["facts"]:
            raise ValueError("Unsupported SMH seam function shape")
        for parameter in function["abiParameters"]:
            if (
                parameter["type"]
                not in {"i32", "isolate_thread", "ptr<void>", "ptr<ptr<void>>", "ptr<u8>"}
                or parameter["facts"]
            ):
                raise ValueError("Unsupported SMH seam parameter shape")


def generate(check=False):
    classes = generator()
    destination = ROOT / "abi/generated"
    java_sources = {}
    files = {}
    with tempfile.TemporaryDirectory() as temporary:
        for target in TARGETS:
            output = Path(temporary) / target
            subprocess.run(
                [
                    "java",
                    "-cp",
                    str(classes),
                    "dev.elide.myna.cli.Main",
                    "generate",
                    str(ROOT / "abi/smh.seam"),
                    "--target",
                    target,
                    "--out",
                    str(output),
                ],
                check=True,
            )
            validate_description(json.loads((output / "seam.json").read_text()))
            for path in sorted(output.iterdir()):
                relative = Path(target) / path.name
                data = path.read_text()
                if path.suffix in {".java", ".rs"}:
                    data = (
                        "// SPDX-FileCopyrightText: 2026 SMH contributors\n// SPDX-License-Identifier: Apache-2.0 OR MIT\n"
                        + data
                    )
                if path.name == "SmhNative.java":
                    java_sources[target] = data
                files[relative] = data
    source = """// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
// Generated by tools/myna_bindings.py. Edit abi/smh.seam, then regenerate.
package dev.elide.smh.processor;
import java.util.Map;
final class AbiBindings {
  private AbiBindings() {}
  private static final Map<String, String> SOURCES = Map.ofEntries(
"""
    source += ",\n".join(
        'Map.entry("' + target + '", """\n' + data + '""")' for target, data in java_sources.items()
    )
    source += """
);
  static String source(String target) { return SOURCES.get(target); }
}
"""
    formatter_version = tomllib.loads((ROOT / "mise.toml").read_text())["tools"][
        "google-java-format"
    ]
    formatter = subprocess.check_output(
        ["mise", "which", "google-java-format", "--tool=google-java-format@" + formatter_version],
        text=True,
    ).strip()
    formatted = subprocess.check_output([formatter, "-"], input=source, text=True)
    target = ROOT / "src/main/java/dev/elide/smh/processor/AbiBindings.java"
    if {p.relative_to(destination) for p in destination.rglob("*") if p.is_file()} - files.keys():
        raise ValueError("Unexpected stale generated ABI output")
    if check:
        for relative, data in files.items():
            final = destination / relative
            if not final.is_file() or final.read_text() != data:
                raise ValueError("Generated ABI drift: " + str(relative))
        if not target.is_file() or target.read_text() != formatted:
            raise ValueError("Generated Java ABI templates have drifted")
    else:
        for relative, data in files.items():
            final = destination / relative
            final.parent.mkdir(parents=True, exist_ok=True)
            final.write_text(data)
        target.write_text(formatted)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    generate(args.check)


if __name__ == "__main__":
    main()
