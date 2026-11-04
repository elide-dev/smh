#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Compare Clang's lowered C ABI with Myna contracts; optionally check a real SVM header."""

import argparse
import json
import re
import subprocess
from pathlib import Path

if __package__:
    from .myna_bindings import ROOT, TARGETS
    from .qualify_compiler import sha256, tool_identity
else:
    from myna_bindings import ROOT, TARGETS
    from qualify_compiler import sha256, tool_identity

EXPECTED = {
    "graal_create_isolate": ("i32", ["ptr", "ptr", "ptr"]),
    "graal_tear_down_isolate": ("i32", ["ptr"]),
    "smh_abi_tag": ("i64", ["ptr", "i32"]),
    "smh_run": ("i32", ["ptr", "ptr"]),
}


def signatures(ir):
    result = {}
    for name, expected in EXPECTED.items():
        declarations = re.findall(r"^declare\s+(.+?)\s+@" + name + r"\((.*?)\).*$", ir, re.M)
        if len(declarations) != 1:
            raise ValueError("Missing or ambiguous lowered ABI declaration: " + name)
        returned, arguments = declarations[0]
        if set(returned.split()[:-1]) - {"dso_local", "dso_preemptable", "dllimport", "noundef"}:
            raise ValueError("Unexpected return ABI or calling convention: " + name)
        if re.search(
            r"\b(signext|zeroext|sret|byval|inalloca|fastcc|coldcc)\b", returned + " " + arguments
        ):
            raise ValueError("Unexpected calling convention or extension: " + name)
        returned = returned.split()[-1]
        parts = [p.strip().split() for p in arguments.split(",")] if arguments else []
        for part in parts:
            if any(
                token != "noundef" and not re.fullmatch(r"%[A-Za-z_][A-Za-z_0-9]*", token)
                for token in part[1:]
            ):
                raise ValueError("Unexpected ABI parameter attribute: " + name)
        parameters = [p[0] for p in parts]
        if (returned, parameters) != expected:
            raise ValueError("Lowered C ABI differs from SMH's integer/pointer contract: " + name)
        result[name] = {"return": returned, "parameters": parameters}
    return result


def lower(compiler, target, header, output):
    source = output.with_suffix(".c")
    source.write_text(
        '#include "'
        + header.name
        + '"\n'
        + "\n".join(f"__typeof__(&{name}) smh_probe_{name} = &{name};" for name in EXPECTED)
        + "\n"
    )
    argv = [
        compiler,
        "-target",
        target,
        "-ffreestanding",
        "-std=gnu17",
        "-Werror",
        "-S",
        "-emit-llvm",
        "-I",
        str(header.parent),
        str(source),
        "-o",
        str(output),
    ]
    subprocess.run(argv, check=True)
    return {
        "command": argv,
        "headerSha256": sha256(header),
        "irSha256": sha256(output),
        "signatures": signatures(output.read_text()),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", default="clang")
    parser.add_argument("--output", type=Path, default=ROOT / "build/abi-lowering")
    parser.add_argument("--svm-header", type=Path)
    parser.add_argument("--svm-target", choices=TARGETS)
    args = parser.parse_args()
    if bool(args.svm_header) != bool(args.svm_target):
        parser.error("--svm-header and --svm-target must be supplied together")
    args.output.mkdir(parents=True, exist_ok=True)
    records = {}
    for target in TARGETS:
        directory = ROOT / "abi/generated" / target
        record = lower(args.compiler, target, directory / "seam.h", args.output / (target + ".ll"))
        contract = directory / "seam.ll"
        if signatures(contract.read_text()) != record["signatures"]:
            raise ValueError("Myna LLVM contracts disagree with Clang: " + target)
        record["contractSha256"] = sha256(contract)
        records[target] = record
    if args.svm_header:
        records["svm"] = lower(
            args.compiler, args.svm_target, args.svm_header.resolve(), args.output / "svm.ll"
        )
    (args.output / "evidence.json").write_text(
        json.dumps(
            {
                "compiler": tool_identity(args.compiler),
                "targets": records,
                "scope": "Cross-compiled C ABI lowering only; native execution and ThinLTO contract application are separate.",
            },
            indent=2,
        )
        + "\n"
    )
    print(
        "Integer/pointer ABI lowering verified for six targets",
        "and the supplied SVM header" if args.svm_header else "",
    )


if __name__ == "__main__":
    main()
