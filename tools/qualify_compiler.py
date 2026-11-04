#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Qualify concrete AOT sink/border fixtures with final compiler graphs and broken controls."""

import argparse
import datetime
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROOF = "dev.elide.smh.proof.ProofMain"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def final_lir(text):
    stages = re.findall(r"begin_cfg\s+(.*?)\s+end_cfg", text, flags=re.S)
    final = [s for s in stages if re.match(r'name "After FinalCodeAnalysisStage"', s)]
    if len(final) != 1 or "LIR" not in final[0] or "instruction " not in final[0]:
        raise ValueError("Missing or ambiguous final LIR; an early graph is insufficient")
    return final[0]


def load_graphs(directory):
    graphs = {}
    for path in sorted(Path(directory).glob("*.cfg")):
        text = path.read_text()
        method = re.search(r'^\s*method "SubstrateHostedCompilation-[^\[]+\[(.+?)\(', text, re.M)
        if not method:
            continue
        name = method.group(1).replace("$", ".")
        graphs.setdefault(name, []).append(
            {"path": str(path), "sha256": sha256(path), "text": text, "lir": final_lir(text)}
        )
    if not graphs:
        raise ValueError("No final Native Image compilation graphs found")
    return graphs


def verify_sinks(graphs, inventory):
    if not inventory:
        raise ValueError("Empty sink inventory")
    evidence = []
    for case in inventory:
        if case["sink"] != "jmh-1.37-consume":
            raise ValueError("Unexpected sink policy in qualification fixture")
        name = case["adapter"].replace("$", ".")
        instances = graphs.get(name, [])
        if not instances:
            raise ValueError("Missing compiled adapter: " + name)
        for graph in instances:
            nodes = len(re.findall(r"instruction BLACKHOLE value:", graph["lir"]))
            if nodes == 0 or "jdk.graal.compiler.nodes.debug.BlackholeNode" not in graph["text"]:
                raise ValueError("Compiler blackhole absent from final LIR: " + name)
            evidence.append(
                {"method": name, "finalBlackholes": nodes, "graphSha256": graph["sha256"]}
            )
    return evidence


def verify_disabled_sinks(graphs, inventory):
    """A broken control must remove every sink, without losing compilation evidence."""
    if not inventory:
        raise ValueError("Empty sink inventory")
    evidence = []
    for case in inventory:
        name = case["adapter"].replace("$", ".")
        instances = graphs.get(name, [])
        if not instances or case["sink"] != "jmh-1.37-consume":
            raise ValueError("Incomplete broken-control fixture inventory: " + name)
        for graph in instances:
            if (
                "instruction BLACKHOLE" in graph["lir"]
                or "jdk.graal.compiler.nodes.debug.BlackholeNode" in graph["text"]
            ):
                raise ValueError("Broken control still contains a compiler blackhole: " + name)
            evidence.append({"method": name, "finalBlackholes": 0, "graphSha256": graph["sha256"]})
    return {"expectedRejection": "All compiler blackholes absent", "methods": evidence}


def verify_borders(graphs):
    instances = graphs.get(PROOF + ".measure", [])
    if not instances:
        raise ValueError("Missing compiled clock/benchmark border fixture")
    evidence = []
    for graph in instances:
        calls = []
        for record in re.findall(r"\bnr\s+-?\d+\s+<\|@\s+(.*?)\s+<\|@\s+<\|@", graph["lir"], re.S):
            if "instruction " not in record or "CALL_" not in record:
                continue
            direct = re.search(r"callTarget: HostedMethod<([^\s>]+)", record)
            if direct:
                callee = direct.group(1)
            else:
                # C function pointers have no named callTarget. Use the innermost frame,
                # not an outer nanoTime frame on an unrelated fatal-error call.
                frame = re.search(r"reference-map:[^\n]*\n([^\n]+)", record)
                callee = frame.group(1) if frame else "unknown"
            calls.append({"callee": callee, "instruction": record})
        clocks = [
            i
            for i, call in enumerate(calls)
            if re.search(
                r"clock_gettime|mach_absolute_time|QueryPerformanceCounter|nanoTime", call["callee"]
            )
        ]
        invocations = [
            i
            for i, call in enumerate(calls)
            if re.search(r"BenchmarkCase.invoke|Benchmarks[.$]B\d+.invoke", call["callee"])
        ]
        if len(clocks) != 2 or len(invocations) != 1 or not clocks[0] < invocations[0] < clocks[1]:
            raise ValueError(
                "Final clock/invocation call order could not be established: " + graph["path"]
            )
        evidence.append(
            {"method": PROOF + ".measure", "orderedCalls": calls, "graphSha256": graph["sha256"]}
        )
    return evidence


def command(argv, log=None, cwd=ROOT):
    if log is None:
        return subprocess.check_output(list(map(str, argv)), cwd=cwd, text=True)
    with Path(log).open("w") as output:
        subprocess.run(
            list(map(str, argv)), cwd=cwd, stdout=output, stderr=subprocess.STDOUT, check=True
        )
    return None


def tool_identity(executable):
    path = Path(shutil.which(executable) or executable).resolve(strict=True)
    files = {str(path): sha256(path)}
    # Native Image launchers alone do not identify their compiler implementation.
    for parent in path.parents:
        if (parent / "lib/modules").is_file():
            for relative in ("lib/modules", "release"):
                file = parent / relative
                if file.is_file():
                    files[str(file)] = sha256(file)
            break
    return {"executable": str(path), "files": files}


def qualify(output, levels, with_pgo):
    output.mkdir(parents=True, exist_ok=False)
    # Assemble first, then freeze every classpath input. No running compiler reads mutable build JARs.
    command(["elide", "run", "assemble"], output / "assembly.log")
    snapshot = output / "snapshot"
    snapshot.mkdir()
    for name in ("smh.jar", "smh-runtime.jar"):
        shutil.copy2(ROOT / "build" / name, snapshot / name)
    shutil.copytree(ROOT / "build/lib", snapshot / "lib")
    runtime = [
        snapshot / "smh-runtime.jar",
        *sorted((snapshot / "lib").glob("gson-*.jar")),
        *sorted((snapshot / "lib").glob("jmh-core-*.jar")),
        *sorted((snapshot / "lib").glob("nativeimage-*.jar")),
        *sorted((snapshot / "lib").glob("word-*.jar")),
    ]
    classes, generated = output / "classes", output / "generated"
    classes.mkdir()
    generated.mkdir()
    classpath = os.pathsep.join(map(str, [classes, *runtime]))
    processorpath = os.pathsep.join(
        map(str, [snapshot / "smh.jar", *sorted((snapshot / "lib").glob("*.jar"))])
    )
    sources = [
        ROOT / "tests/qualification/dev/elide/smh/proof" / name
        for name in ("ProofMain.java", "SinkCases.java")
    ]
    command(
        [
            "elide",
            "javac",
            "--",
            "--release",
            "25",
            "-cp",
            classpath,
            "-processorpath",
            processorpath,
            "-processor",
            "dev.elide.smh.processor.BenchmarkProcessor",
            "-d",
            classes,
            "-s",
            generated,
            *sources,
        ],
        output / "javac.log",
    )
    inventory = json.loads(command(["java", "-cp", classpath, PROOF, "37"]))
    policy = json.loads(command(["java", "-cp", classpath, PROOF, "--policy"]))
    version = command(["native-image", "--version"])
    suffix = ".exe" if os.name == "nt" else ""
    variants = []

    def build(name, optimization, extra=(), broken=False):
        work = output / name
        work.mkdir()
        binary = work / ("proof" + suffix)
        graphs = work / "graphs"
        flags = [
            f"-O{optimization}",
            *policy,
            "--parallelism=4",
            "-J-Xmx4g",
            f"-H:NeverInline={PROOF}.measure,{PROOF}.scalarDiscarded",
            "-H:MethodFilter=dev.elide.smh.*.*",
            "-H:Dump=:2",
            "-H:+PrintBackendCFG",
            f"-H:DumpPath={graphs}",
            *extra,
        ]
        argv = ["native-image", *flags, "-cp", classpath, PROOF, "-o", binary]
        command(argv, work / "build.log")
        raw = command([binary, "37", "measure"])
        (work / "execution.json").write_text(raw)
        rows = json.loads(raw)
        if len(rows) != len(inventory) or any(row["elapsedNanos"] <= 0 for row in rows):
            raise ValueError("Native qualification fixture did not execute its complete matrix")
        compiled = load_graphs(graphs)
        if broken:
            sinks = verify_disabled_sinks(compiled, inventory)
            borders = []
        else:
            sinks = verify_sinks(compiled, inventory)
            borders = verify_borders(compiled)
        command(["objdump", "-d", binary], work / "disassembly.txt")
        record = {
            "name": name,
            "command": list(map(str, argv)),
            "binarySha256": sha256(binary),
            "sinks": sinks,
            "borders": borders,
            "brokenControl": broken,
            "artifacts": {
                p.relative_to(work).as_posix(): sha256(p)
                for p in sorted(work.rglob("*"))
                if p.is_file()
            },
        }
        variants.append(record)
        (work / "evidence.json").write_text(json.dumps(record, indent=2) + "\n")
        return binary

    for level in levels:
        build("O" + level, level)
    build(
        "disabled-intrinsic",
        levels[-1],
        ["-H:DisableIntrinsics=org.openjdk.jmh.infra.Blackhole.consume"],
        broken=True,
    )
    if with_pgo:
        instrumented = build("instrument", "2", ["--pgo-instrument"])
        profile = output / "training.iprof"
        command(
            [instrumented, f"-XX:ProfilesDumpFile={profile}", "37", "measure"],
            output / "training.log",
        )
        if not profile.is_file() or profile.stat().st_size == 0:
            raise ValueError("Training did not produce a PGO profile")
        build("pgo", "3", [f"--pgo={profile}"])
    result = {
        "schemaVersion": 1,
        "scope": "Concrete sink and scheduled-call fixtures only; no general accuracy or CPU serialization claim",
        "host": {"os": platform.system(), "architecture": platform.machine()},
        "compilerVersion": version,
        "tools": {name: tool_identity(name) for name in ("native-image", "java", "objdump")},
        "profile": {"path": str(profile), "sha256": sha256(profile)} if with_pgo else None,
        "policy": policy,
        "inventory": inventory,
        "variants": variants,
        "inputs": {
            p.relative_to(output).as_posix(): sha256(p)
            for p in sorted(snapshot.rglob("*"))
            if p.is_file()
        },
        "sources": {p.relative_to(ROOT).as_posix(): sha256(p) for p in sources},
    }
    (output / "qualification.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--levels", default="1,2,3")
    parser.add_argument("--with-pgo", action="store_true")
    args = parser.parse_args()
    levels = args.levels.split(",")
    if not levels or any(level not in {"1", "2", "3"} for level in levels):
        parser.error("--levels accepts 1,2,3")
    output = args.output or ROOT / "build/qualification" / datetime.datetime.now(
        datetime.UTC
    ).strftime("%Y%m%dT%H%M%S%fZ")
    qualify(output.resolve(), levels, args.with_pgo)
    print("Concrete compiler fixtures and deliberate broken control verified:", output)


if __name__ == "__main__":
    main()
