#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Real native layer publication, relocation, and changed/unchanged benchmark builds."""

import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from build import ROOT
from verification import artifact_identity, require


def main():
    root = ROOT / "build/layer-tests"
    root.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix="run-", dir=root))
    sdk = evidence / "sdk"
    sdk.mkdir()
    for name in ("smh.jar", "smh-runtime.jar"):
        shutil.copy2(ROOT / "build" / name, sdk / name)
    shutil.copytree(ROOT / "build/lib", sdk / "lib")
    java = shutil.which("java")
    cli = [
        java,
        "-cp",
        os.pathsep.join([str(sdk / "smh.jar"), str(sdk / "lib/*")]),
        "dev.elide.smh.cli.Main",
    ]
    source = evidence / "Work.java"
    source.write_text(
        "package layers; public class Work { private int n; @org.openjdk.jmh.annotations.Benchmark public int work() { return ++n; } }"
    )
    config = evidence / "build.json"
    settings = {
        "home": str(sdk),
        "sources": [str(source)],
        "output": str(evidence / "image"),
        "nativeArgs": ["--parallelism=4", "-J-Xmx4g"],
    }
    config.write_text(json.dumps(settings))
    # Different output directories must still share the base-layer producer lock.
    processes = []
    for index in range(2):
        out = (evidence / f"producer-{index}.stdout.log").open("w")
        err = (evidence / f"producer-{index}.stderr.log").open("w")
        processes.append(
            (
                subprocess.Popen(
                    cli
                    + [
                        "build",
                        "--config",
                        str(config),
                        "--base-only",
                        "--output",
                        str(evidence / f"out-{index}"),
                    ],
                    stdout=out,
                    stderr=err,
                ),
                out,
                err,
            )
        )
    for process, out, err in processes:
        code = process.wait(timeout=600)
        out.close()
        err.close()
        require(code == 0, f"Layer producer failed: {evidence}")
    logs = [(evidence / f"producer-{i}.stderr.log").read_text() for i in range(2)]
    require(sum("Layer ready" in log for log in logs) == 1, logs)
    require(sum("Layer cache hit" in log for log in logs) == 1, logs)
    layers = list((sdk / "layers").glob("*/SHA256"))
    require(len(layers) == 1, layers)
    key = layers[0].parent.name
    relocated = evidence / "relocated-sdk"
    sdk.rename(relocated)
    settings["home"] = str(relocated)
    config.write_text(json.dumps(settings))
    cli[2] = os.pathsep.join([str(relocated / "smh.jar"), str(relocated / "lib/*")])
    run = evidence / "run.json"
    run.write_text(
        json.dumps({"warmupIterations": 0, "measurementIterations": 1, "measurementNanos": 100000})
    )
    timings = []
    for case in ("relocated-layer", "unchanged", "changed-source"):
        if case == "changed-source":
            source.write_text(source.read_text().replace("++n", "n += 3"))
        start = time.perf_counter()
        with (
            (evidence / f"{case}.stdout.log").open("w") as out,
            (evidence / f"{case}.stderr.log").open("w") as err,
        ):
            result = subprocess.run(
                cli
                + [
                    "bench",
                    "--build-config",
                    str(config),
                    "--config",
                    str(run),
                    "--display",
                    "plain",
                ],
                stdout=out,
                stderr=err,
                timeout=600,
            )
        require(result.returncode == 0, f"Native build/run failed: {evidence}/{case}")
        log = (evidence / f"{case}.stderr.log").read_text()
        if case != "unchanged":
            require("Layer cache hit" in log and "Layer ready" not in log, log)
        else:
            require("Cache hit" in log, log)
        rows = json.loads((evidence / f"{case}.stdout.log").read_text())
        require(rows[0]["smh"]["execution"]["hostBuild"]["layerKey"] == key, rows)
        timings.append(
            {
                "case": case,
                "seconds": time.perf_counter() - start,
                "scope": "CLI identity checks, native build if changed, and one fresh measurement",
            }
        )
    # Hide both the original image and SDK: an accidental absolute loader path must fail.
    binary = Path(rows[0]["smh"]["execution"]["hostBuild"]["binary"])
    deployed = evidence / "deployed"
    deployed.mkdir()
    for path in binary.parent.iterdir():
        if (
            path == binary
            or path.name.startswith("libsmh-base-")
            or path.name.startswith("smh-base-")
        ):
            shutil.copy2(path, deployed / path.name)
    hidden_image = binary.parent.with_name(binary.parent.name + "-hidden")
    hidden_sdk = relocated.with_name("hidden-sdk")
    binary.parent.rename(hidden_image)
    relocated.rename(hidden_sdk)
    environment = dict(os.environ)
    environment.pop("LD_LIBRARY_PATH", None)
    try:
        result = subprocess.run(
            [str(deployed / binary.name), str(run)],
            cwd=deployed,
            env=environment,
            capture_output=True,
            text=True,
            timeout=60,
        )
        (evidence / "deployed.stdout.log").write_text(result.stdout)
        (evidence / "deployed.stderr.log").write_text(result.stderr)
        require(result.returncode == 0 and len(json.loads(result.stdout)) == 1, result.stderr)
    finally:
        hidden_sdk.rename(relocated)
        hidden_image.rename(binary.parent)
    (evidence / "evidence.json").write_text(
        json.dumps(
            {
                "identity": artifact_identity(
                    ROOT, [relocated / "smh.jar", relocated / "smh-runtime.jar", binary]
                ),
                "layerKey": key,
                "concurrentProducers": 2,
                "nativeLayerBuilds": 1,
                "timings": timings,
            },
            indent=2,
        )
        + "\n"
    )
    print(evidence)


if __name__ == "__main__":
    main()
