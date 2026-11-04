#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Gate Rust coverage without confusing absent branch instrumentation with full coverage."""

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def summarize(report, branch_only=False):
    expected = {p.resolve() for p in (ROOT / "src").rglob("*.rs")} | {ROOT / "build.rs"}
    files = {}
    totals = {kind: {"count": 0, "covered": 0} for kind in ("lines", "branches")}
    for section in json.loads(Path(report).read_text())["data"]:
        for file in section["files"]:
            path = Path(file["filename"]).resolve()
            if path not in expected:
                continue
            if path in files:
                raise ValueError("Duplicate production Rust coverage: " + str(path))
            counters = {}
            for kind in totals:
                counter = file["summary"][kind]
                count, covered = counter["count"], counter["covered"]
                if type(count) is not int or type(covered) is not int or not 0 <= covered <= count:
                    raise ValueError("Invalid Rust coverage counter")
                counters[kind] = {"count": count, "covered": covered}
                for field in ("count", "covered"):
                    totals[kind][field] += counters[kind][field]
            files[path] = counters
            if not counters["lines"]["count"]:
                raise ValueError("Missing Rust line instrumentation: " + str(path))
    if files.keys() != expected:
        raise ValueError("Coverage omits production Rust sources: " + str(expected - files.keys()))
    kind = "branches" if branch_only else "lines"
    policy = json.loads((ROOT / "quality-policy.json").read_text())["coverage"]["productionRust"]
    target = policy["branch" if branch_only else "line"]
    counter = totals[kind]
    if not counter["count"] or not totals["lines"]["count"]:
        raise ValueError("Missing Rust " + kind + " instrumentation")
    return {
        "scope": "Production Rust, including build.rs; compiler-defined coverage counters",
        "gate": kind,
        "target": target,
        "passed": counter["covered"] * 100 >= counter["count"] * target,
        "totals": totals,
        "files": {str(p.relative_to(ROOT)): c for p, c in files.items()},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--branch-only", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    result = summarize(args.report, args.branch_only)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: result[k] for k in ("gate", "target", "passed", "totals")}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
