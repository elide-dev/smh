<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Assurance and platform evidence

SMH's initial release is functional, experimental AOT tooling. **Ordinary results
remain `accuracy: unqualified`.** Passing coverage, stable samples, and concrete
compiler fixtures are useful evidence; none proves general measurement accuracy.
This ledger separates executed checks from outstanding obligations.

## Hosted evidence

The initial release passed [quality checks](https://github.com/elide-dev/smh/actions/runs/41938178623),
[portable JVM checks](https://github.com/elide-dev/smh/actions/runs/41938178643),
[security analysis](https://github.com/elide-dev/smh/actions/runs/41938178738), and
[native packaging](https://github.com/elide-dev/smh/actions/runs/41938178688).
The tagged source also passed [fuzzing](https://github.com/elide-dev/smh/actions/runs/41940035267)
and [Scorecard](https://github.com/elide-dev/smh/actions/runs/41940035092).
Use each run's revision, logs, and artifacts to establish its exact scope.

| Check | Executed scope | Important limit |
| --- | --- | --- |
| JVM coverage | Linux amd64/arm64, macOS arm64, Windows amd64; 75 tests | JVM execution does not qualify native machine code. |
| Portable JVM behavior | 74 tagged tests on all six OS/architecture combinations below | A checksum-verified Elide-built bundle runs on each native host JVM. |
| Rust lifecycle and line coverage | 11 tests on Linux amd64/arm64, macOS arm64, Windows amd64 | Failure-injection providers are Rust fixtures, not SVM. |
| ASan/LSan | Linux amd64/arm64, bootstrap/loader/test providers; three required known-bug controls | Standard library is not rebuilt; SVM heaps and JIT code are outside scope. |
| Miri | Seven compatible tests for Linux x86-64 and ARM64 targets, two borrow models, seeds 0/1 | Target interpretation is not native ARM ABI execution. |
| Jazzer | Run configuration and Callgrind parser on Linux amd64/arm64 | Independent oracles, coverage feedback, and crash/replay controls; not exhaustive proof. |

The JDK fallback change adds three portable compiler-selection tests. Its
[hosted quality matrix](https://github.com/elide-dev/smh/actions/runs/41942589723)
runs 75 tests, with 74 in the portable subset. The initial hosted runs linked
above had 72 total and 71 portable tests. The packaged 0.1.1 candidate CLI also
built and ran the getting-started benchmark with Elide absent from `PATH`, using
`JAVA_HOME`; both native parameter cases passed on local Linux amd64.

Java gates require 99% production lines and branches, and 100% for the
mathematical kernel. The compiler-selection snapshot is 1450/1457 lines (99.52%),
890/897 branches (99.22%), and kernel 151/151 lines plus 68/68 branches.
Rust line coverage is gated separately at 99%; nightly branch counters use a
separate object directory and compiler-defined denominator. Missing source files
or missing instrumentation fail validation. See [Validation](VALIDATION.md).

## Platform ledger

| Native host | JVM behavior | Full native benchmark workflow | Compiler/measurement qualification |
| --- | --- | --- | --- |
| Linux amd64 | Hosted pass | CLI, layered O1/O2/O3, monolithic PGO, shared image, Callgrind O2 | Concrete local fixtures pass; general accuracy open |
| Linux arm64 | Hosted pass | Open | Open |
| macOS amd64 | Hosted pass | Open; pinned Elide has no host distribution | Open |
| macOS arm64 | Hosted pass | Open | Open |
| Windows amd64 | Hosted pass | Open | Open |
| Windows arm64 | Hosted pass | Open; pinned Elide has no host distribution | Open |

Native release archives currently target Linux amd64. The SDK's portable JVM
coverage does not imply that every host's Native Image layers, PGO, shared
libraries, or compiler sinks have been exercised.

## Measurement claims

| Area | Existing evidence | Still required |
| --- | --- | --- |
| Arithmetic and stabilization | Independent reference traces, boundary/property tests, full kernel coverage, retained raw histories and bounded sample stopping | Broader mutation evidence, hard time/work limits, confidence inference |
| Compiler sinks | Twelve generated adapter methods retain `BlackholeNode` and final-LIR `BLACKHOLE` at O1/O2/O3 and monolithic PGO; disabled-intrinsic control removes them | Qualification per compiler/profile and architecture; general hoisting/CSE and CPU-order proof |
| Timing borders | Lifecycle/failure tests; scheduled-call LIR fixture; native work-count and score checks | Production control-flow dominance/postdominance, CPU clock serialization, full boundary qualification |
| Calibration | Frozen O2 monolithic/layered fixtures check probes, counts, stopping, and score arithmetic | Timer resolution, empty-region precision, compiler/CPU qualification |
| Callgrind | O2 monolithic/layered runs, repeatability, affine work scaling, fixture/warmup exclusion, rejected disabled/unbounded collection | More native hosts/profiles, online convergence, Codspeed |
| PGO and layers | Monolithic instrument/train/use and optimization-aware layer builds | Layered instrumentation where supported, shared profile dumping, concurrent immutable cache publication |
| FFI | Six-target Myna generation/C ABI lowering, real Linux shared image, failure cleanup and full fingerprint checks | Real SVM on other hosts, SVM instrumentation, FFI fuzzing and additional threads |
| JIT | Explicit `jitQuiescence: unknown` | JIT/Crema execution and compilation/deoptimization event stabilization |

Local compiler evidence uses Oracle GraalVM 25.3.4.1 / JDK 25.0.4.1 on Linux
amd64. Historical development artifacts live under ignored `build/qualification/`,
`build/simulation-tests/`, `build/calibration-tests/`, and `build/abi-lowering/`.
These are local records, not published release attestations. The original sink
campaign predates implementation/profile hashing in the qualification tool;
those identities need a new campaign, not a retrospective assertion.

Known limits remain visible: JMH compiler-only blackholes are required; the
runtime padding fallback is unqualified on SVM. Invocation-call and batch-loop
costs remain in scores. A previous overlapping SDK/layer build was correctly
rejected for changed JARs, motivating the sequential-build rule. Finite sanitizer
and Miri runs do not prove soundness for all executions.

## Repository and release integrity

The public repository has secret scanning/push protection, required checks,
linear history, and blocked force-pushes/deletion. Administrators retain bypass
access. Codecov uploads, CodeQL, and Scorecard have executed successfully;
Dependabot proposes pinned dependency/action updates.

Releases are immutable. Version `0.1.0` was published before assets were attached
and remains source-only. The corrected pipeline stages and verifies assets in a
draft before publication. Independent review history, broader signed provenance,
and a complete dependency/license inventory remain open; Myna's pinned upstream
tree lacked a license file when inspected.

OpenSSF Scorecard and the Best Practices badge are separate assessments.
**No Passing/Silver/Gold badge is claimed.** Gold requires human review,
participation, and access-control evidence that workflow configuration cannot
supply. See [Gold criteria](https://www.bestpractices.dev/en/criteria/2) and
[Scorecard checks](https://github.com/ossf/scorecard/blob/main/docs/checks.md).

New evidence must identify the revision, command, machine, compiler/runtime,
artifact hashes, assumptions, and whether execution was native or interpreted.
Retain failures and negative controls alongside successes. Update this ledger
when evidence changes; the [roadmap](../ROADMAP.md) tracks remaining work.
