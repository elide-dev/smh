<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Assurance case and evidence ledger

This document records claims to establish, not a certification already earned.
Current implementation evidence is local Linux amd64 functional testing; the
initial harness does not yet establish accurate measurement or compiler borders.

| Claim | Required evidence | Current state |
| --- | --- | --- |
| Correct operation/time arithmetic | Independent reference traces; boundary/property/mutation tests; full kernel coverage | Deterministic traces pass for all three modes/seven units; statistics kernel 100% line/branch; mutation/native accuracy evidence open |
| Work survives optimization | Installed-intrinsic probes, positive/negative kernels, IR/disassembly, dynamic controls per compiler profile | Typed returns and explicit JMH blackholes implemented; twelve final-LIR sink fixtures pass O1/O2/O3 and PGO locally, with disabled-intrinsic control. General qualification open |
| Measured borders contain the intended work | State-machine invariants, exception tests, compiler/CPU ordering tests, native observer controls | Runner lifecycle/failure tests pass with suppressed cleanup errors; generated multi-state teardown and completed-state setup unwinding pass; compiler/CPU borders open |
| Warmup/result stability is auditable | Synthetic stable/drifting/noisy traces; bounded stopping; retained histories; JIT event evidence | Sample-bounded warmup/measurement convergence and histories implemented/tested; time/work limits and JIT events open |
| Simulation measures declared regions | Real Callgrind/Codspeed traces, exact events, broken-border controls, deterministic work budgets | Local Linux Callgrind O2 monolithic/layered fixtures pass repeatability, affine work scaling, fixture/warmup exclusion, and disabled/unbounded controls. Codspeed, online simulator convergence, and other native hosts open |
| PGO comparisons are reproducible | Instrument/train/use provenance, profile/artifact hashes, validation per supported layer/runtime combination | Initial monolithic workflow functional; qualification open |
| FFI does not corrupt state | Myna ABI generation/checks, Rust/C/SVM differential tests, sanitizer/fuzzing evidence, failure injection | Pinned Myna bindings; six-target Java compilation and C ABI lowering; real Linux shared-image run; eleven Rust lifecycle/failure tests; ASan/LSan controls and Miri interpretation pass. SVM instrumentation and native platform qualification open |
| Claimed platforms work natively | Actual runner/hardware logs and artifact identity for each of six OS/architecture cells | Linux amd64 functional only |
| Code coverage is comprehensive | All production Java packages plus separate Rust/native reports; line and branch thresholds; no hidden exclusions | Java: 1429/1436 lines (99.51%), 870/875 branches (99.43%); kernel 151/151 lines, 68/68 branches. Rust stable: 144/145 lines (99.31%) including build.rs; nightly: 30/30 instrumented branch outcomes. Native/SVM instrumentation open |
| Supply chain and maintenance are secure | Hardened CI, reviews, signed provenance, dependency policy, OpenSSF evidence | Hosted CodeQL and Scorecard pass; required checks and branch protection configured; signed provenance and review-policy qualification remain open |

The coverage snapshot above is from the November 4, 2026 local Elide JUnit run
after blackhole, Myna, Callgrind, and in-process compiler/CLI/build tests: 72 JUnit tests pass.
`tools/coverage_gate.py` includes
all owned production sources, separates compiler-generated definitions/test code,
and refuses missing source coverage. Real in-process javac tests exercise the
annotation processor, generated adapter behavior, rejected inputs, and diagnostics.
Build tests separately exercise planning, injected command/artifact failures,
archive extraction, stale-artifact cleanup, and real process exit statuses.
Injected marker artifacts are never presented as native compiler evidence.
Remaining Java branches include actual Native Image execution detection and the
host-specific loader-path decision; a passing coverage target is not full proof.

The native Micronaut CLI was rebuilt locally. An O2 layered benchmark run
exercised adaptive warmup and measurement across the complete example matrix;
every result retained both phase histories and explicitly reported unqualified
accuracy. [Hosted portable execution](https://github.com/elide-dev/smh/actions/runs/41936178179)
passes all 71 tagged tests on each of six native JVM hosts. JVM coverage passes
on the four Elide-supported hosts. Earlier local evidence logs include
`build/native-final-milestone.log`, `build/verified-adaptive-build.out`,
`build/verified-adaptive-run.out`, and `build/callgrind-probe/portable.log`.

The current compiler fixture matrix is recorded under
`build/qualification/sinks-pgo/qualification.json`: O1/O2/O3, monolithic PGO
instrument/use, and a disabled-intrinsic control. All twelve generated adapter
methods contain `BlackholeNode` and final-LIR `BLACKHOLE` operations in each
positive variant. All twelve lose those operations in the disabled control;
`disabled-intrinsic/complete-negative-control.json` records that additional check.
Compiler dumps were archived and every member's hash reverified before removing
duplicate unpacked copies; `archive.json` binds `compiler-graphs.tar.gz` to the
original manifest. Disassembly, binaries, build logs, execution output, and frozen
classpath inputs remain alongside the archive. This run predates the added
compiler-implementation/profile hashing in the qualification tool; those fields
must be collected by a new run, not retrospectively asserted.

The scheduled-call fixture finds two clock calls surrounding one invocation in
final LIR. It does not establish CFG dominance/postdominance, CPU ordering, or the
production runner's full timing boundary. Generated invocation methods are marked
non-inlineable by the AOT policy; invocation-call overhead remains in scores.
JMH compiler-only mode is required, with Blackhole initialized during image
building. The runtime padding path failed on SVM's object layout during probing;
it is not a qualified fallback. The compiler intrinsic must be checked for every
profile. Ordinary results retain `accuracy: unqualified` and
`sinkQualification: unverified`.

Myna evidence includes `build/tools/myna-final-check.log`,
`build/abi-lowering/evidence.json` (six C targets and a real SVM header),
`build/tools/myna-native-final.json` (four example results through the rebuilt Rust
bootstrap), and `build/tools/rust-loader-tests.log`. Generation and ABI rules are
documented in `docs/FFI.md`. The Windows adaptation is a limited local subset,
not an upstream or native-execution qualification.

Rust coverage uses separate directories for stable and nightly compiler objects;
mixing those objects produced misleading counters during investigation and is
not accepted evidence. Current isolated profiles are
`build/reports/rust-line-summary.json` and `rust-branch-summary.json`, produced by
`mise run coverage-rust` and `coverage-rust-branch`. Stable runtime sources cover
128/128 lines; the one uncovered build-script line is the unsupported-target
panic diagnostic. Nightly's compiler-defined branch counters cover 30/30 outcomes
and its line counters cover 141/141; compiler versions use different line maps.
Those branch counters do not cover every match, try operation, or panic path.
The gate requires every production Rust source and `build.rs`, rejects duplicate
or invalid counters, and never accepts absent branch instrumentation as 100%.
Pinned Nextest produces actual eleven-test JUnit XML for each profile, with retries
disabled. The identity manifests record compiler, coverage tool, test runner, and
commands. CI configures separate Codecov uploads for Java and Rust coverage and
test results; hosted JVM uploads now pass alongside the coverage gates. Python orchestration coverage,
native helper/SVM coverage, and broader mutation/fuzzing evidence remain open.

One intervening build was rejected after runtime packaging overlapped a layer
build and changed its classpath JAR. Native Image's compatibility check detected
the mismatch (`build/final-adaptive-build.out`). The successful rerun held
artifacts fixed. Immutable artifact snapshots and concurrent cache publication
are still open requirements; this failure is retained with the passing evidence.

The variance regression includes a nearly constant `1e15` signal checked against
an independent decimal reference; centering before scaling corrected a real
precision error found during development. Seeded property checks and 100%
coverage do not prove arbitrary floating-point input correctness.

## Platform qualification ledger

The run-configuration fuzz target passed 100,000 inputs with strict JSON field
types and independent exact-value round-trip oracles:
`build/fuzz-run-config/20261104T231204917555Z/evidence.json`. It observed 87/89
RunConfig lines and 96/102 branches; the filesystem entrypoint is outside this
in-memory target. StabilityPolicy reached 15/15 lines and 12/12 branches in this
campaign. The intentional regex API's injection hook is disabled for this target
only; the initial finding and rationale are retained in FUZZING.md.


Miri evidence is in `build/miri/20261104T225834578002Z/evidence.json` (Linux
x86-64 target) and `build/miri/20261104T230003793334Z/evidence.json` (Linux ARM64
target). Each interpreted target passed seven compatible tests under strict
provenance, Stacked/Tree Borrows, and seeds 0/1, with clean and use-after-free
controls. These are target interpretation results, not execution on ARM hardware.

The Callgrind parser's 100,000-input Jazzer campaign passed with all 55 lines
and 48 branch outcomes observed. Its independent exact-counter oracle and
deliberately broken target/replay also passed. Evidence:
`build/fuzz-callgrind/20261104T230616970926Z/evidence.json`. This parser-only
coverage is separate from whole-project JaCoCo and does not prove correctness
for all inputs. Other fuzz targets remain open.


The Rust ASan profile passed locally on Linux amd64:
`build/rust-asan/20261104T225929627944Z/evidence.json`. Separate heap-overflow,
use-after-free, and 123-byte leak controls in instrumented shared providers were
detected through the real bootstrap/ABI path, followed by eleven passing instrumented
tests. Stable Rust tests
also passed after the fixture compiler changes. This excludes an unrebuilt
standard library, real SVM libraries/heaps, and JIT code. See SANITIZERS.md.


Explicit wall batching was exercised in a Linux amd64 O2 monolithic AOT image
with batch size 64, two parameter cases, and both average-time and throughput
modes. Warmup and measurement operation counts were positive multiples of 64;
every sample score matched an independent calculation from elapsed nanoseconds
and operation count. Evidence and artifact hash:
`build/wall-batch-native/evidence.json`. This validates behavior and normalization;
it does not qualify compiler/CPU ordering for the new batch path.


Callgrind region evidence is under `build/simulation-tests/regions-v2/evidence.json`.
Both O2 monolithic and layered images passed six real workloads each: baseline,
repeat, increased warmup, heavy trial/iteration setup and teardown, double work,
and triple work. Each parameter case retained three identical instruction counts.
For the 16-element fixture, monolithic counts were 21,509 / 43,009 / 64,509 for
100 / 200 / 300 invocations; layered counts were 23,150 / 46,250 / 69,350.
These are artifact-specific fixture observations, not universal performance
constants. The differences between layer profiles remain visible.

Disabled and unbounded collection were rejected by the production importer for
both variants. An earlier proposed negative control only changed the initial
collection flag while retaining `--toggle-collect`; Callgrind overrode that
setting, so the control was ineffective. The failed experiment is retained under
`build/simulation-tests/regions/`; it is not counted as a passing control.
The rebuilt native Micronaut CLI also ran both image variants successfully,
producing `build/native-cli-simulation-{monolithic,layered}/results.json` with five
retained samples per parameter case. The final manifest-channel verification reran both variants and both broken
collection controls successfully; evidence is in
`build/simulation-channel-verification/evidence.json`. Benchmark stdout is
retained separately from the machine-readable manifest. Native CLI logs are in
`build/callgrind-probe/`. See `docs/SIMULATION.md` for boundary/counter semantics.

Callgrind currently uses function collection controls, not native client requests.
Its fixed-budget engine deliberately rejects invocation fixtures and online
convergence policies. Additional backends, native hosts, optimizer profiles,
dynamic/JIT event monitoring, and simulation convergence remain open.

| Native host | Portable JVM behavior | Native CLI/benchmark workflow | Measurement/compiler qualification |
| --- | --- | --- | --- |
| Linux amd64 | 71 tests pass in hosted CI | O1/O2/O3 layered; full-image PGO; Myna shared-image path; Callgrind O2 monolithic/layered | Twelve concrete sinks and scheduled-call fixture at O1/O2/O3 + PGO; concrete Callgrind regions and broken controls; general accuracy open |
| Linux arm64 | 71 tests pass in hosted CI | Open | Open |
| macOS amd64 | 71 tests pass in hosted CI | Open; pinned Elide has no distribution for this host | Open |
| macOS arm64 | 71 tests pass in hosted CI | Open | Open |
| Windows amd64 | 71 tests pass in hosted CI | Open | Open |
| Windows arm64 | 71 tests pass in hosted CI | Open; pinned Elide has no distribution for this host | Open |

The six-platform JVM job executes a checksum-verified bundle compiled with Elide;
the launcher uses native runner Java. That does not exercise a native SMH binary.
The current toolchain release has Linux amd64/arm64 and macOS arm64 builds;
missing distributions are capability gaps, not successful qualification.

The canonical repository is public at `elide-dev/smh`. Secret scanning and push
protection are enabled. Main has required CI checks, linear history, and blocked
force-pushes/deletion; administrators retain bypass access for repository setup.
Codecov uploads, CodeQL, and Scorecard have executed successfully. Signed release
provenance, independent review history, and the Best Practices badge remain open.

Each evidence record must identify source revision/tree digest, command, machine,
toolchain and runtime versions/hashes, applicable assumptions, output artifacts,
and whether it was obtained on native hardware or through emulation. Retain
negative controls and failures as well as passing cases. Local ignored build
logs are useful during development but are not published release evidence.

Coverage is necessary but not a correctness proof. ASan/TSan applicability must
name the actual instrumented code: a sanitized C library does not establish
coverage of the SVM managed heap or JIT-generated code. The toolchain/fork profiles
must demonstrate their instrumentation with known-bug controls.

OpenSSF Scorecard is an automated repository assessment. The Best Practices badge
has Passing/Silver/Gold criteria and needs a separate evidence-based submission.
Gold also depends on project participation, review history, and access controls;
workflow YAML cannot supply that human evidence.

References: [Gold criteria](https://www.bestpractices.dev/en/criteria/2),
[Scorecard checks](https://github.com/ossf/scorecard/blob/main/docs/checks.md),
[toolchain sanitizer contracts](https://github.com/elide-dev/toolchain#sanitizers),
[Callgrind manual](https://valgrind.org/docs/manual/cl-manual.html).

## Automatic batch selection

`mise run test-calibration` builds frozen monolithic and layered O2 fixtures.
Local Linux amd64 evidence in `build/calibration-tests/20261104T232406043959Z`
passes raw-probe, work-count, stopping-policy, and score-arithmetic checks.
Some phases reach their configured sample limit; these results remain visible
and are not relabeled stable. The kernel gate includes the calibrator and its
policy. Timer resolution, empty-region overhead, and CPU/compiler qualification
remain separate obligations.
