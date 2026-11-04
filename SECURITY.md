<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Security policy

SMH is pre-release software. No supported production release or security service
level is declared yet. Report potential vulnerabilities privately to the
repository's maintainers through GitHub private vulnerability reporting when
enabled. Until that channel is enabled, contact a maintainer privately to arrange
an encrypted report before sending sensitive details. Do not publish an exploit
or personal information in a public issue.

The canonical repository is [elide-dev/smh](https://github.com/elide-dev/smh).
It currently has internal visibility; public OpenSSF evidence and badge
eligibility require a separate hosting decision. Code security/secret scanning
were disabled when inspected on November 4, 2026; workflow files do not enable
those repository services or prove that they have run.

Include affected source revision/version, platform/toolchain, reproduction steps,
impact, and any proposed mitigation. Reports may concern native memory/ABI
handling, configuration/CLI parsing, unsafe artifact loading, process execution,
dependency/supply-chain compromise, or misleading measurement validity.

Benchmarks, annotation processors, compiler plugins, custom observers, native
libraries, toolchains, and user-provided build options execute code. SMH does not
currently provide an isolation boundary for untrusted benchmark submissions.
CI must separate untrusted pull requests from release credentials and privileged
deployment workflows.

The roadmap tracks enabling and testing private reporting, defining supported
releases and response targets, conducting security review, and publishing signed
release/provenance and vulnerability-response evidence. No OpenSSF badge or
security qualification is claimed by this policy.
