#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Assemble the application, then reuse an unchanged native benchmark build."""

import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent
(root / "build").mkdir(exist_ok=True)
log = root / "build/assemble.log"
print("Assembling invoice-app…", file=sys.stderr, flush=True)
with log.open("w") as output:
    result = subprocess.run(
        [sys.executable, "assemble.py"], cwd=root, stdout=output, stderr=subprocess.STDOUT
    )
if result.returncode:
    print(log.read_text(), file=sys.stderr)
    sys.exit(result.returncode)
print(f"Application ready · log: {log.relative_to(root)}", file=sys.stderr)
sys.exit(subprocess.call(["smh", "bench", *sys.argv[1:]], cwd=root))
