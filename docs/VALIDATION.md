<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Validation

Run commands from the repository root after `mise trust` and `mise install`.
Use `mise exec --` for tool commands outside a mise-activated shell.

## Everyday checks

```sh
mise run lint
mise run test-tools
mise run coverage
mise run coverage-rust
mise run check-abi
```

Java coverage requires **99% lines and branches across production code**, and
**100% for the mathematical kernel**. Rust line coverage has a separate 99% gate.
Nightly Rust branch coverage is a separate profile; compiler-defined branch
counters do not represent every possible Rust control-flow edge.
Do not lower thresholds, exclude production code, or combine languages to hide a gap.

| Task | Command | Evidence |
| --- | --- | --- |
| JVM behavior and coverage | `mise run coverage` | `.dev/reports/`, `build/reports/coverage-summary.json` |
| Portable JVM behavior | `mise run test-portable` | Portable bundle and native host JVM reports |
| Rust line coverage | `mise run coverage-rust` | `build/reports/`, nextest JUnit |
| Rust branch coverage | `mise run coverage-rust-branch` | Isolated nightly objects and branch summary |
| Generated Myna bindings | `mise run check-abi` | Exact generated-output comparison |
| Six-target C/LLVM ABI lowering | `mise run check-abi-lowering` | `build/abi-lowering/` |

## Native and compiler checks

Install GraalVM JDK 25 and its host compiler. Run these **sequentially**: JVM
functional tests may rebuild the SDK JARs that native builds consume.

```sh
mise run test-native
mise run test-pgo
mise run test-calibration
mise run test-simulation
mise run qualify-compiler
mise run qualify-pgo
```

PGO needs a supporting compiler distribution. Simulation requires Linux and
Valgrind. Qualification fixtures inspect compiler output and use deliberately
broken controls; they do not certify all compiler/CPU measurement boundaries.
Compiler profiles and frozen inputs must remain attached to their evidence.

## Fuzzing and memory safety

| Check | Command | Scope |
| --- | --- | --- |
| Callgrind parser fuzzing | `mise run fuzz-callgrind` | Mutated dumps plus independent exact-counter oracle |
| Run configuration fuzzing | `mise run fuzz-run-config` | Typed JSON and exact-value oracle |
| Rust ASan/LSan | `mise run test-rust-asan` | Bootstrap, loader dependency, test harness/providers on Linux |
| Miri | `mise run test-miri` | Compatible Rust lifecycle tests, strict provenance, two borrow models |

Each campaign requires a known-bug control before accepting production results.
See [fuzzing procedures](FUZZING.md) for seeds, crash reproduction, and retained
corpora; see [sanitizer scope](SANITIZERS.md) for instrumented code and exclusions.
These checks do not instrument SVM managed heaps or JIT-generated code. TSan and
fork-specific instrumentor qualification remain on the [roadmap](../ROADMAP.md).

## Hosted checks and releases

CI uses SHA-pinned actions, `harden-runner` on Linux, `elide-dev/setup-elide`, and
`elide-dev/toolchain` in applicable jobs. Coverage and JUnit XML are sent to
Codecov through OIDC. CodeQL, dependency review, Dependabot, and OpenSSF Scorecard
cover repository maintenance and supply-chain checks.

Release Please maintains version changes and release notes. After its PR passes
required checks and merges, the workflow creates a **draft**, builds the SDK and
Linux amd64 native archives, verifies checksums before and after upload, and
publishes the immutable release. Never publish a draft before its assets are
complete. A failed upload can be retried while the release remains a draft;
published immutable assets cannot be repaired in place.

Repository checks and passing tests are evidence, not an OpenSSF Gold rating or
a proof of measurement accuracy. The [assurance ledger](ASSURANCE.md) distinguishes
hosted results, local experiments, and outstanding obligations.
