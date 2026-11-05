#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Exercise the real native CLI in a POSIX terminal, including SIGINT and pipe behavior."""

import codecs
import json
import os
import re
import select
import signal
import struct
import subprocess
import sys
import time
from pathlib import Path

from verification import require

ROOT = Path(__file__).resolve().parents[1]
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def terminal(command, destination, *, interrupt=False, width=100, height=35, environment=None):
    import fcntl
    import pty
    import termios

    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
    process = subprocess.Popen(
        command,
        cwd=ROOT,
        stdin=slave,
        stdout=slave,
        stderr=slave,
        env={
            **os.environ,
            "TERM": "xterm-256color",
            "COLUMNS": str(width),
            "LINES": str(height),
            **(environment or {}),
        },
        start_new_session=True,
    )
    os.close(slave)
    started = time.monotonic()
    chunks = []
    decoder = codecs.getincrementaldecoder("utf-8")()
    interrupted = False
    try:
        with destination.open("w", encoding="utf-8") as recording:
            recording.write(json.dumps({"version": 2, "width": width, "height": height}) + "\n")
            while True:
                require(time.monotonic() - started < 30, "Terminal child timed out")
                if not select.select([master], [], [], 0.1)[0]:
                    continue
                try:
                    data = os.read(master, 65536)
                except OSError:
                    break  # PTY slave closed after the process exited.
                if not data:
                    break
                chunks.append(data)
                recording.write(
                    json.dumps([time.monotonic() - started, "o", decoder.decode(data)]) + "\n"
                )
                if interrupt and not interrupted and b"\x1b[?25l" in b"".join(chunks):
                    os.killpg(process.pid, signal.SIGINT)
                    interrupted = True
            status = process.wait(timeout=5)
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        os.close(master)
    return status, b"".join(chunks).decode()


def retained_report(screen, configured_output, iterations):
    plain = ANSI.sub("", screen)
    raw = next(
        line.split("Raw report: ", 1)[1].strip()
        for line in plain.splitlines()
        if "Raw report: " in line
    )
    report = ROOT / raw
    rows = json.loads(report.read_bytes())
    require(rows == json.loads(configured_output.read_bytes()), "Raw report values changed")
    require(len(rows) == 2, "Both invoice sizes must be retained")
    require(
        all(len(row["primaryMetric"]["rawData"][0]) == iterations for row in rows),
        "Rounded display must retain every raw iteration",
    )
    return report


def histogram(screen, width):
    plain = ANSI.sub("", screen)
    for expected in ("Iterations", "Iteration score", "median", "p99", "µs/op", "└"):
        require(expected in plain, f"Missing histogram content: {expected}")
    require(any(block in plain for block in "▁▂▃▄▅▆▇█"), "Missing histogram bins")
    require(("Run order" in plain) == (width >= 110), "Incorrect run-order chart width threshold")
    require("iterations" in plain, "Histogram must name iteration scores")
    require("CV" not in plain, "Default results should keep statistics compact")
    for line in plain.splitlines():
        if "│" in line or "└" in line:
            require(len(line) <= width, f"Chart exceeds {width} columns: {line}")
    require(
        plain.index("Iteration score") < plain.index("Complete"),
        "Completed cases must render before the process reports completion",
    )
    require(plain.count("JDK ") == 1, "Environment must appear once at the end")
    require("not-assessed" not in plain, "Unassessed states must stay out of the display")
    require("NATIVE BENCHMARK LAB" not in plain, "Obsolete banner must not return")
    require("38;2;" in screen, "Expected 24-bit color")
    require("\x1b[?25h" in screen, "Completed results must restore the cursor")


def main():
    require(sys.platform != "win32", "Real PTY check requires a POSIX host")
    cli = ROOT / "build/smh"
    binary = ROOT / "examples/invoice-app/build/benchmarks/benchmarks"
    require(
        cli.is_file() and binary.is_file(), "Run mise run native and mise run test-invoice first"
    )
    output = ROOT / "build/terminal-tests"
    output.mkdir(parents=True, exist_ok=True)
    config = output / "run.json"
    configured_output = output / "configured-output.json"
    config.write_text(
        json.dumps(
            {
                "warmupIterations": 0,
                "measurementIterations": 3,
                "measurementNanos": 100000000,
                "output": str(configured_output),
            }
        )
    )
    command = [str(cli), "run", str(binary), "--config", str(config)]
    baseline = output / "baseline.json"
    for width in (58, 100, 140):
        status, screen = terminal(
            [*command, "--display", "tui"], output / f"run-{width}.cast", width=width
        )
        require(status == 0, screen)
        histogram(screen, width)
        for expected in ("⠋", "⠙"):
            require(expected in screen, f"Missing animated frame: {expected}")
        baseline.write_bytes(retained_report(screen, configured_output, 3).read_bytes())
    config.write_text(
        json.dumps(
            {
                "warmupIterations": 100,
                "warmupNanos": 1000000,
                "measurementIterations": 1000,
                "measurementNanos": 1000000,
                "output": str(configured_output),
            }
        )
    )
    status, screen = terminal(
        [*command, "--display", "tui"], output / "distribution-140.cast", width=140
    )
    require(status == 0, screen)
    histogram(screen, 140)
    retained_report(screen, configured_output, 1000)
    status, comparison = terminal(
        [*command, "--display", "tui", "--compare", str(baseline)], output / "comparison.cast"
    )
    require(status == 0, comparison)
    plain_comparison = ANSI.sub("", comparison)
    for expected in ("vs baseline", "Change", "less time", "more time", "%", "│"):
        require(expected in plain_comparison, f"Missing comparison content: {expected}")
    require("38;2;" in comparison, "Expected 24-bit color")
    require(plain_comparison.count("JDK ") == 1, "Environment must appear once at the end")
    require(
        plain_comparison.index("lines=10") < plain_comparison.index("Complete"),
        "Comparison rows must render before the process reports completion",
    )
    require(
        "Iteration score" not in plain_comparison,
        "Comparison rows should use centered change bars instead of repeating distributions",
    )
    retained_report(comparison, configured_output, 1000)
    config.write_text(
        json.dumps(
            {"warmupIterations": 0, "measurementIterations": 1, "measurementNanos": 5000000000}
        )
    )
    status, screen = terminal(
        [*command, "--display", "tui"], output / "interrupt.cast", interrupt=True
    )
    require(status != 0, "SIGINT must not report success")
    require("\x1b[?25h" in screen, "SIGINT must restore the terminal cursor")
    config.write_text(
        json.dumps(
            {
                "warmupIterations": 0,
                "measurementIterations": 1,
                "measurementNanos": 1000000,
                "output": str(configured_output),
            }
        )
    )
    for flags in ([], ["--display", "tui"]):
        result = subprocess.run(
            [*command, *flags], cwd=ROOT, capture_output=True, timeout=30, check=True
        )
        require(json.loads(result.stdout) == json.loads(configured_output.read_bytes()))
        require(b"\x1b" not in result.stdout, "Machine output must not contain terminal controls")
        require((b"\x1b" in result.stderr) == bool(flags), "Incorrect automatic terminal policy")
    for name, environment in (("no-color", {"NO_COLOR": "1"}), ("dumb", {"TERM": "dumb"})):
        status, screen = terminal(command, output / f"{name}.cast", environment=environment)
        require(status == 0, screen)
        require("\x1b" not in screen, "Automatic plain output must not emit terminal controls")
        require(json.loads(screen) == json.loads(configured_output.read_bytes()))
    print(
        "Native PTY charts, widths, streaming comparisons, retained JSON, plain policy and SIGINT passed:",
        output,
    )


if __name__ == "__main__":
    main()
