<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Contributing

SMH is dual licensed under Apache-2.0 or MIT, at the recipient's option.
Contributions must be eligible for both licenses. Sign off commits using
`git commit -s` to certify the [Developer Certificate of Origin](https://developercertificate.org/).

Follow the feature/evidence backlog in [ROADMAP.md](ROADMAP.md). Keep changes
focused and reviewable. Java uses the Google Java Format version pinned in mise;
run `mise exec -- python3 tools/format_java.py --fix`. Rust uses rustfmt and Clippy
with warnings denied. Python orchestration must remain portable and avoid shell
interpolation. All owned source/configuration files carry SPDX copyright and
`Apache-2.0 OR MIT` identifiers.

Tests must verify externally observable behavior and independent invariants.
For measurement mathematics use independent reference results, property tests,
and adversarial traces. Exercise failures and boundary cases, not just success.
Targets are >=99% production line coverage and >=99% branch coverage, and 100%
for the mathematical kernel. Do not exclude production paths, change the
denominator, or lower a threshold to make a change pass. Record any current gaps.

Changes to timers, borders, blackholes, statistics, concurrency, unsafe code, or
the FFI require an updated assurance record and appropriate compiler/native
evidence. Diagnostic logging must stay outside measured regions. Never describe
a target, unexecuted workflow, unsupported platform, or passing stub as verified.

Before release, changes require review by someone other than their author.
Reviewers check correctness, failure cleanup, measurement assumptions, tests and
coverage, portability, security, dependency/ABI impact, and linked evidence.
Repository rules enforcing review, branch protection, and access policies must
be configured and verified separately; this document does not assert they exist.

Useful initial tasks are the deterministic reference tests, configuration error
cases, capability probes, and report fixtures identified by stable IDs in the
roadmap. Report security issues using [SECURITY.md](SECURITY.md).
