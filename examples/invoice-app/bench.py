#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Run the benchmark with application preparation managed by SMH."""

import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parent
sys.exit(subprocess.call(["smh", "bench", *sys.argv[1:]], cwd=root))
