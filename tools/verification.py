# SPDX-FileCopyrightText: 2026 SMH contributors
# SPDX-License-Identifier: Apache-2.0 OR MIT
"""Verification checks that remain enabled when Python runs with -O."""


def require(condition, message="Verification failed"):
    if not condition:
        raise AssertionError(message)
