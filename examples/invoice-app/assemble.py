#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Build the application independently; SMH consumes only its packaged JAR."""

import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent
(root / "build").mkdir(exist_ok=True)
subprocess.run(["elide", "build", "compile-java-main"], cwd=root, check=True)
subprocess.run(
    [
        "elide",
        "jar",
        "--",
        "--create",
        "--date=2000-01-01T00:00:00Z",
        "--file",
        "build/invoice-app.jar",
        "--main-class",
        "example.invoice.InvoiceApp",
        "-C",
        ".dev/jvm/classes/main/java",
        ".",
    ],
    cwd=root,
    check=True,
)
