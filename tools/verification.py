# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Verification checks that remain enabled when Python runs with -O."""

import hashlib
import platform
import subprocess


def require(condition, message="Verification failed"):
    if not condition:
        raise AssertionError(message)


def artifact_identity(root, paths):
    """Identify the actual artifacts, including builds from an uncommitted worktree."""
    return {
        "revision": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True
        ).strip(),
        "dirty": bool(
            subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True)
        ),
        "platform": platform.platform(),
        "artifacts": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths},
    }
