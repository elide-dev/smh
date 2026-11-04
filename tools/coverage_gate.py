#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Inspect JaCoCo XML without concealing uncovered production packages."""

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KERNEL = {
    "WindowStatistics",
    "StabilityPolicy",
    "StabilityMonitor",
    "BatchCalibrationPolicy",
    "BatchCalibrator",
}


def summarize(report):
    root = ET.parse(report).getroot()
    totals = {
        "productionJava": {"LINE": [0, 0], "BRANCH": [0, 0]},
        "mathematicalKernel": {"LINE": [0, 0], "BRANCH": [0, 0]},
    }
    classes = []
    names = set()
    for package in root.findall("package"):
        for cls in package.findall("class"):
            name = cls.attrib["name"]
            if not name.startswith("dev/elide/smh/"):
                continue
            if name in names:
                raise ValueError("Duplicate coverage class: " + name)
            names.add(name)
            # Keep generated definitions visible, but do not confuse their compiler with our processor.
            generated = any(part.startswith("$") for part in name.split("/"))
            source = Path(package.attrib["name"]) / cls.attrib.get("sourcefilename", "")
            if source.is_absolute() or ".." in source.parts:
                raise ValueError("Invalid coverage source path: " + str(source))
            production = not generated and (ROOT / "src/main/java" / source).is_file()
            group = (
                "productionJava"
                if production
                else "generated"
                if generated
                else "test-or-generated"
            )
            counters = {}
            for counter in cls.findall("counter"):
                kind = counter.attrib["type"]
                if kind not in {"LINE", "BRANCH"}:
                    continue
                if kind in counters:
                    raise ValueError("Duplicate coverage counter: " + name + ":" + kind)
                missed = int(counter.attrib["missed"])
                covered = int(counter.attrib["covered"])
                if missed < 0 or covered < 0:
                    raise ValueError("Coverage counters cannot be negative")
                counters[kind] = {"missed": missed, "covered": covered}
                if production:
                    totals[group][kind][0] += missed
                    totals[group][kind][1] += covered
                    simple = name.rsplit("/", 1)[-1].split("$", 1)[0]
                    if simple in KERNEL:
                        totals["mathematicalKernel"][kind][0] += missed
                        totals["mathematicalKernel"][kind][1] += covered
            classes.append(
                {"name": name, "source": source.as_posix(), "group": group, "counters": counters}
            )
    if not classes or sum(totals["productionJava"]["LINE"]) == 0:
        raise ValueError("Coverage report contains no production Java lines")
    reported_sources = {entry["source"] for entry in classes if entry["group"] == "productionJava"}
    expected_sources = {
        path.relative_to(ROOT / "src/main/java").as_posix()
        for path in (ROOT / "src/main/java").rglob("*.java")
    }
    missing = expected_sources - reported_sources
    if missing:
        raise ValueError("Coverage report omits production sources: " + ", ".join(sorted(missing)))
    return {"groups": totals, "classes": classes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument(
        "--baseline",
        action="store_true",
        help="Record deficits without claiming the final gate passed",
    )
    parser.add_argument(
        "--kernel-only",
        action="store_true",
        help="Enforce the kernel milestone; report the global deficit",
    )
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/coverage-summary.json")
    args = parser.parse_args()
    policy = json.loads((ROOT / "quality-policy.json").read_text())
    summary = summarize(args.report)
    passed = True
    for group, counters in summary["groups"].items():
        for kind, (missed, covered) in counters.items():
            total = missed + covered
            percentage = 100 * covered / total if total else None
            target = policy["coverage"][group]["line" if kind == "LINE" else "branch"]
            meets = total > 0 and covered * 100 >= target * total
            print(
                f"{group} {kind}: {covered}/{total} ({percentage if percentage is not None else 'missing'}%; target {target}%)"
            )
            if not args.kernel_only or group == "mathematicalKernel":
                passed = passed and meets
    summary["globalGatePassed"] = all(
        sum(pair) > 0
        and pair[1] * 100
        >= policy["coverage"][group]["line" if kind == "LINE" else "branch"] * sum(pair)
        for group, counters in summary["groups"].items()
        for kind, pair in counters.items()
    )
    summary["mode"] = (
        "baseline" if args.baseline else "kernel-milestone" if args.kernel_only else "full-gate"
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n")
    if args.baseline:
        print("Coverage baseline recorded; this is not a passing final quality gate.")
        return 0
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
