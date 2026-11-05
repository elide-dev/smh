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
import tempfile
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
            key: value
            for key, value in {
                **os.environ,
                "TERM": "xterm-256color",
                "COLUMNS": str(width),
                "LINES": str(height),
                **(environment or {}),
            }.items()
            if value is not None
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
    require("\x1b[?1049h" in screen, "Live report must use a temporary screen")
    require("\x1b[?1049l" in screen, "Final report must restore the normal screen")
    plain = ANSI.sub("", screen.rsplit("\x1b[?1049l", 1)[-1])
    for expected in ("Distribution", "Sample order", "lines=10", "lines=1000", "µs/op", "n="):
        require(expected in plain, f"Missing shared chart content: {expected}")
    require("█" in plain, "Missing histogram bins")
    for line in plain.splitlines():
        if line.startswith("    "):
            require(len(line) <= width, f"Chart exceeds {width} columns: {line}")
    require(plain.count("JDK ") == 1, "Environment must appear once at the end")
    require("not-assessed" not in plain, "Unassessed states must stay out of the display")
    require("38;2;" in screen, "Expected 24-bit color")
    require("\x1b[?25h" in screen, "Completed results must restore the cursor")


def candidate_cases(cli, output):
    """Real command argv, rotating launches and transient frames survive native packaging."""
    for width in (40, 58, 100, 140):
        target = Path(tempfile.mkdtemp(prefix="candidate-", dir=output)) / "run"
        command = [
            str(cli),
            "--mode",
            "startup",
            "--launches",
            "3",
            "--display",
            "tui",
            "--output",
            str(target),
            "--candidate",
            sys.executable,
            "-c",
            "import time; time.sleep(.01)",
            "--candidate",
            sys.executable,
            "-c",
            "import time; time.sleep(.03)",
        ]
        status, screen = terminal(command, output / f"candidates-{width}.cast", width=width)
        require(status == 0, screen)
        require(
            "\x1b[?1049h" in screen and "\x1b[?1049l" in screen, "Missing live screen lifecycle"
        )
        final = ANSI.sub("", screen.rsplit("\x1b[?1049l", 1)[-1])
        require("Startup · round" not in final, "Progress leaked into final report")
        for expected in (
            "Distribution",
            "Sample order",
            "Fastest observed",
            "significance not assessed",
        ):
            require(expected in final, f"Missing candidate result: {expected}")
        rows = json.loads((target / "results.json").read_bytes())
        require(len(rows) == 2)
        for index, row in enumerate(rows):
            observations = row["smh"]["observations"]
            require(len(observations) == 3)
            require([o["slot"] for o in observations] == [(index - r) % 2 for r in range(3)])
            require(all(o["endpointNanos"] == o["completionNanos"] > 0 for o in observations))
        require(
            json.loads((target / "comparison.json").read_bytes())["significanceAssessed"] is False
        )
    failed = Path(tempfile.mkdtemp(prefix="candidate-failure-", dir=output)) / "run"
    status, screen = terminal(
        [
            str(cli),
            "--mode",
            "startup",
            "--display",
            "tui",
            "--output",
            str(failed),
            "--candidate",
            sys.executable,
            "-c",
            "pass",
            "--candidate",
            sys.executable,
            "-c",
            "raise SystemExit(7)",
        ],
        output / "candidate-failure.cast",
    )
    require(status != 0 and "\x1b[?1049l" in screen, "Failure must restore the live screen")
    require(
        not (failed / "results.json").exists(),
        "Failed comparison must not produce a successful report",
    )
    require((failed / "partial-results.json").is_file())
    require((failed / "candidate-1/launch-0/failure.json").is_file())
    interrupted = Path(tempfile.mkdtemp(prefix="candidate-interrupt-", dir=output)) / "run"
    status, screen = terminal(
        [
            str(cli),
            "--mode",
            "startup",
            "--display",
            "tui",
            "--output",
            str(interrupted),
            "--candidate",
            sys.executable,
            "-c",
            "pass",
            "--candidate",
            sys.executable,
            "-c",
            "import time; time.sleep(20)",
        ],
        output / "candidate-interrupt.cast",
        interrupt=True,
    )
    require(status != 0, "Interrupted startup must not report success")
    require("\x1b[?1049l" in screen, "SIGINT must restore the alternate screen")
    require(not (interrupted / "results.json").exists())


def error_cases(cli, output):
    """The packaged CLI must expose readable failures and stable machine records."""
    source = output / "ErrorWork.java"
    source.write_text("class ErrorWork {}")
    build = output / "missing-dependency.json"
    build.write_text(
        json.dumps(
            {
                "home": str(ROOT / "build"),
                "sources": [str(source.resolve())],
                "classpath": [str((output / "missing-app.jar").resolve())],
            }
        )
    )
    broken_source = output / "Broken.java"
    broken_source.write_text("class Broken { BROKEN_FOR_ERROR_TEST }")
    broken_build = output / "compiler-failure.json"
    broken_build.write_text(
        json.dumps(
            {
                "home": str(ROOT / "build"),
                "sources": [str(broken_source.resolve())],
                "output": str((output / "failed-build").resolve()),
                "layers": False,
            }
        )
    )
    malformed = output / "malformed.json"
    malformed.write_text("{")
    missing = output / "absent-config.json"
    cases = [
        (["build", "--config", str(build)], "SMH-CLASSPATH-MISSING", 1),
        (["build", "--config", str(missing)], "SMH-FILE-MISSING", 1),
        (["build", "--config", str(malformed)], "SMH-CONFIG-JSON", 1),
        (["build"], "SMH-USAGE", 2),
        (["build", "--config", str(broken_build)], "SMH-BUILD-FAILED", 1),
    ]
    for arguments, code, status in cases:
        for machine in (False, True):
            command = [str(cli), *(["--error-format", "json"] if machine else []), *arguments]
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=30)
            require(result.returncode == status, result.stderr)
            require(not result.stdout, "Failures must not contaminate result stdout")
            require("\x1b" not in result.stderr, "Piped errors must be plain")
            (output / f"{code}-{'json' if machine else 'text'}.log").write_text(result.stderr)
            if machine:
                records = [
                    json.loads(line) for line in result.stderr.splitlines() if line.startswith("{")
                ]
                require(len(records) == 1, result.stderr)
                error = records[0]
                require(error["code"] == code and error["exitCode"] == status, error)
                require(error["advice"], "Recovery advice missing")
                if error["diagnostics"]:
                    require(Path(error["diagnostics"]).is_file(), "Trace file missing")
            else:
                require(code in result.stderr and "Next steps" in result.stderr, result.stderr)
                require(
                    "\tat " not in result.stderr and "at dev.elide" not in result.stderr,
                    result.stderr,
                )
            if code == "SMH-BUILD-FAILED":
                require(
                    "BROKEN_FOR_ERROR_TEST" not in result.stderr,
                    "Compiler output leaked into the summary",
                )
                log = next(
                    line.split("log: ", 1)[1]
                    for line in result.stderr.splitlines()
                    if "log: " in line
                )
                require(
                    "BROKEN_FOR_ERROR_TEST" in Path(log).read_text(), "Compiler diagnostics lost"
                )
    verbose = subprocess.run(
        [str(cli), "build", "--config", str(broken_build), "--verbose"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=30,
    )
    require(verbose.returncode == 1 and "BROKEN_FOR_ERROR_TEST" in verbose.stderr, verbose.stderr)
    status, screen = terminal(
        [str(cli), *cases[0][0]],
        output / "error-color.cast",
        environment={"NO_COLOR": None, "CI": None},
    )
    require(status == 1 and "Next steps" in screen, screen)
    require("\033[38;2;" in screen and "at dev.elide" not in screen, screen)
    status, screen = terminal(
        [str(cli), *cases[0][0]], output / "error-no-color.cast", environment={"NO_COLOR": "1"}
    )
    require(status == 1 and "\033" not in screen, screen)


def main():
    require(sys.platform != "win32", "Real PTY check requires a POSIX host")
    cli = ROOT / "build/smh"
    binary = ROOT / "examples/invoice-app/build/benchmarks/benchmarks"
    require(
        cli.is_file() and binary.is_file(), "Run mise run native and mise run test-invoice first"
    )
    output = ROOT / "build/terminal-tests"
    output.mkdir(parents=True, exist_ok=True)
    error_cases(cli, output)
    candidate_cases(cli, output)
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
