#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Optional real compiler/loader validation; run after mise run native."""

import argparse
import json
import os
import shutil
import subprocess

from verification import require

from build import ROOT

os.chdir(ROOT)
parser = argparse.ArgumentParser()
parser.add_argument("--with-pgo", action="store_true", help="Requires a PGO-capable Native Image")
args = parser.parse_args()
suffix = ".exe" if os.name == "nt" else ""
cli = ROOT / ("build/smh" + suffix)
bootstrap = ROOT / ("build/smh-bootstrap" + suffix)
require(cli.is_file() and bootstrap.is_file(), "Run mise run native first")
out = ROOT / "build/native-tests"
out.mkdir(parents=True, exist_ok=True)


def build(name, *flags):
    dest = out / name
    print(f"Building native validation variant: {name}", flush=True)
    with (out / (name + ".log")).open("w") as log:
        result = subprocess.run(
            [str(cli), "build", "--config", "examples/build.json", "--output", str(dest), *flags],
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    require(result.returncode == 0, f"Build failed; see {out / (name + '.log')}")
    return dest


def execute(dest, *flags):
    config = dest / "run.json"
    config.write_text(
        json.dumps({"warmupIterations": 0, "measurementIterations": 1, "measurementNanos": 1000000})
    )
    result = subprocess.check_output(
        [str(cli), "run", str(dest / ("benchmarks" + suffix)), "--config", str(config), *flags],
        text=True,
    )
    rows = json.loads(result)
    require(len(rows) == 4 and {r["mode"] for r in rows} == {"avgt", "thrpt"}, rows)
    require(all(r["vmName"] == "Substrate VM" for r in rows), rows)
    return rows


keys = []
for optimization in ("1", "2", "3"):
    dest = build("O" + optimization, "-O" + optimization)
    execute(dest)
    manifest = json.loads((dest / "build.json").read_text())
    keys.append(manifest["layerKey"])
require(len(set(keys)) == 3)

# A deployed executable must resolve its layer without the build directory or LD_LIBRARY_PATH.
relocated = out / "relocated"
relocated.mkdir(exist_ok=True)
for file in dest.iterdir():
    if file.name == "benchmarks" + suffix or "smh-base-" in file.name:
        shutil.copy2(file, relocated / file.name)
execute(relocated)

shared = build("shared", "--shared")
config = shared / "run.json"
config.write_text('{"warmupIterations":0,"measurementIterations":1,"measurementNanos":1000000}')
library = json.loads((shared / "build.json").read_text())["binary"]
rows = json.loads(
    subprocess.check_output(
        [str(cli), "run", str(library), "--config", str(config), "--bootstrap", str(bootstrap)],
        text=True,
    )
)
require(len(rows) == 4)

if args.with_pgo:
    instrument = build("instrument", "--no-layers", "--pgo", "instrument")
    profile = out / "training.iprof"
    execute(instrument, "--profile-output", str(profile))
    require(profile.is_file() and profile.stat().st_size > 0)
    optimized = build("pgo", "--no-layers", "--pgo", "use", "--profile", str(profile), "-O3")
    execute(optimized)
    require("PGO: user-provided" in (out / "pgo.log").read_text())
print("Native CLI, optimization variants, relocation, and Rust bootstrap checks passed")
