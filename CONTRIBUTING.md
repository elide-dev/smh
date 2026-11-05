<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Contributing

Start with the [roadmap](ROADMAP.md) and keep changes focused. For repository
navigation and measurement invariants, read [AGENTS.md](AGENTS.md).

## Set up and check a change

Install mise, Python 3, and Java 25. Native checks also require GraalVM's
`native-image` and its host C compiler.

```sh
mise trust
mise install
mise exec -- rustup component add --toolchain 1.91.1 rustfmt clippy
mise run build
mise run lint
mise run test-tools
mise run coverage
mise run coverage-rust
```

Format Java with the pinned formatter:

```sh
mise exec -- python3 tools/format_java.py --fix
```

Rust uses rustfmt and Clippy with warnings denied. Python uses Ruff and must stay
portable; avoid shell interpolation. See [Validation](docs/VALIDATION.md) for
native, compiler, ABI, sanitizer, and fuzzing commands. Do not run packaging/JVM
functional tests concurrently with native builds that consume the same SDK JARs.

## Tests and evidence

Test observable behavior and independent invariants, including failures and
boundary cases. Measurement mathematics needs reference results, property tests,
and adversarial traces. Production Java line and branch coverage must each reach
99%, with 100% for the mathematical kernel; Rust coverage is assessed separately.
Do not change denominators or lower gates to make a change pass.

Changes to timers, borders, blackholes, statistics, concurrency, unsafe code, or
the FFI require updated [assurance evidence](docs/ASSURANCE.md). Name the compiler,
platform, artifact, and actual instrumentation scope. Never describe a target,
unexecuted workflow, unsupported platform, or passing stub as verified.

Before release, changes require review by someone other than their author.
Reviewers check correctness, cleanup, measurement assumptions, portability,
security, coverage, dependency/ABI impact, and evidence. Repository enforcement
and review history are separate evidence; see the assurance ledger for their
current state.

## License and commits

Contributions must be eligible for both Apache-2.0 and MIT. Add SPDX copyright
and `Apache-2.0 OR MIT` identifiers to owned files; asset/JSON annotations live in
`REUSE.toml`. Sign off commits to certify the
[Developer Certificate of Origin](https://developercertificate.org/):

```sh
git commit -s
```

Use conventional commit subjects so Release Please can produce release notes.
Report security issues using [SECURITY.md](SECURITY.md).
