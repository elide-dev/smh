<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# SMH feature and verification roadmap

The product target is reproducible, explainable wall-clock and instruction/cache
simulation benchmarking on amd64 and arm64 across Linux, macOS, and Windows.
Elide remains the project/build tool; Micronaut and Picocli provide the CLI.
PGO and optimization levels are explicit dimensions throughout the build model.

This is the working backlog. Checkboxes mean implemented **and backed by the
listed evidence**, not merely designed. An unavailable platform, missing compiler
intrinsic, failed probe, or absent report must remain visible. A simulation count
must never be labeled as measured time. A converged series is not automatically
an accurate measurement or evidence of JIT quiescence.

## M0 — contracts, provenance, and quality infrastructure

- [x] F00-01: Confirm simulation means instruction/cache simulation (Callgrind/Codspeed).
- [x] F00-02: Adopt `Apache-2.0 OR MIT` licensing (user decision).
- [x] F00-03: Add full license texts and SPDX headers; enforce headers automatically.
  Evidence: `tools/license_headers.py` and its regression tests.
- [x] F00-04: Specify measurement intervals, sinks, fixtures, units, and failure semantics.
  Evidence: `docs/MEASUREMENT.md`; implementation obligations remain tracked below.
- [ ] F00-05: Define typed backend capabilities and report unsupported combinations explicitly.
- [x] F00-06: Configure Elide JUnit XML and JaCoCo XML/HTML reporting.
  Evidence: actual reports under `.dev/reports/` from `elide test`.
- [x] F00-07: Measure the coverage baseline for every production Java package, including processors.
  Evidence: `tools/coverage_gate.py`, `build/reports/coverage-summary.json`; current deficit in assurance ledger.
- [x] F00-08: Enforce >=99% production Java line and >=99% branch coverage; 100% for the mathematical kernel.
  Evidence: actual local JaCoCo report, full gate passes; current snapshot and host scope in `docs/ASSURANCE.md`.
- [x] F00-09: Collect Rust line/region/branch coverage separately; do not average away gaps.
  Evidence: stable line gate 144/145 (99.31%) including build.rs; isolated nightly 30/30
  instrumented branch outcomes. Compiler-defined coverage limits and profiles in assurance ledger.
- [x] F00-10: Enforce formatting, Clippy warnings, workflow linting, license checks, and typed configuration.
  Evidence: `mise run lint`, configuration tests; Java/Python/Rust formatter versions pinned.
- [x] F00-11: Add hardened, SHA-pinned CI using setup-elide and elide-dev/toolchain where supported.
  Evidence: hosted quality/security/portable workflows pass; see `docs/ASSURANCE.md`.
- [x] F00-12: Report coverage and JUnit XML to Codecov using OIDC, with archived local evidence.
  Evidence: hosted Java/Rust coverage and test uploads pass in the quality workflow.
- [ ] F00-13: Pin/update dependencies and action references; record reproducible toolchain identities.
- [x] F00-14: Maintain an assurance case and a platform qualification ledger.
  Evidence: `docs/ASSURANCE.md`; unqualified cells remain explicitly open.

Exit: independently runnable local tests and report checks; validated workflow
syntax; an honest baseline and explicit coverage deficits. M0 does not certify
measurement accuracy or platform support.

## M1 — measurement engine and stabilization

- [x] F01-01: Separate monotonic wall clocks from simulation counters via a typed backend API.
  Evidence: MeasurementBackend selection, clock-free fixed-work engine, distinct counter schema,
  and tests rejecting wall-duration/mode options in simulation. Codspeed remains a separate backend task.
- [x] F01-02: Inject wall clocks for deterministic measurement tests, without a fake production clock.
  Evidence: package-private clock seam and independent `RunnerTest` traces; simulator seam tracked in M3.
- [ ] F01-03: Calibrate timer resolution, empty-region overhead, and batch sizes; report uncertainty.
  Bounded automatic batch selection is implemented and tested with retained probes,
  explicit exhaustion, and monolithic/layered O2 native evidence. Timer resolution,
  empty-region characterization, and uncertainty qualification remain open.
- [x] F01-04: Time batches instead of taking two clock readings for every tiny invocation.
  Explicit wallBatchSize uses one clock pair per batch, counts all operations, and records
  the policy. Deterministic traces cover all three modes. Automatic batch calibration is available (remaining calibration work is F01-03);
  invocation-fixture batches are rejected, and compiler/CPU qualification remains M2.
- [ ] F01-05: Give invocation-fixture benchmarks an explicit measurement policy and overhead qualification.
- [ ] F01-06: Make region entry/exit balanced under every benchmark/fixture/observer failure.
- [x] F01-07: Preserve primary exceptions and attach cleanup failures as suppressed exceptions.
  Runner scopes pass boundary/failure tests. Generated multi-state teardown attempts every
  cleanup and preserves failures, including repeated exception identities and blackhole clearing;
  executable processor tests cover these paths. Failed setup now unwinds completed states
  at all three lifecycle levels, attaching cleanup errors directly to the setup failure.
  The failing state owns recovery of its own partially acquired resources; see MEASUREMENT.md.
- [ ] F01-08: Detect overflow, non-finite values, zero/negative durations, and invalid operation counts.
- [x] F01-09: Add bounded, configurable warmup convergence with raw history and stopping reasons.
  Evidence: `StabilityMonitorTest`, `RunnerTest`, strict JSON policies, all observations retained.
- [ ] F01-10: Add bounded measurement convergence, minimum samples/time, and maximum work/time.
- [ ] F01-11: Detect trend, window drift, dispersion, autocorrelation, and unstable/multimodal runs.
  Trend/drift/dispersion and bounded exhaustion are implemented; autocorrelation/multimodality remain open.
- [ ] F01-12: Distinguish statistical stability from observed JIT compilation/deoptimization quiescence.
- [ ] F01-13: Add runtime/compiler event providers for HotSpot, Graal JIT, Crema, and guest runtimes.
- [x] F01-14: Never silently trim samples or repeat until a favorable result; retain exclusions and reasons.
  Evidence: full phase histories and deterministic stable/unstable stopping tests; no exclusion mechanism.
- [ ] F01-15: Separate discovery, calibration, warmup, training, measurement, and reporting phases.
- [ ] F01-16: Add independent-process forks, randomized/interleaved case order, and reproducible seeds.
- [ ] F01-17: Add multi-thread workers with correctly scoped states and coordinated measurement borders.
- [x] F01-18: Test scores and units against analytically specified, independently computed reference traces.
  Evidence: `RunnerTest` covers three modes, seven units, operation normalization, fixture exclusion, timer wrap.

The descriptive statistics/stopping kernel currently has 100% measured line and
branch coverage, including independent reference/property tests and a regression
for loss of small variance in large nearly constant signals. This is not a proof
of compiler borders, native timing accuracy, or JIT quiescence.

Exit: deterministic tests cover every kernel branch, actual clock calibration is
reported, unstable runs are unmistakable, and adaptive stopping is auditable.

## M2 — compiler blackholes and benchmark signal borders

- [x] F02-01: Consume all returned primitive/object/array values with compiler-recognized blackholes.
  Evidence: typed generated calls and final LIR for twelve real adapters at O1/O2/O3 + PGO
  on local Linux amd64; broader compiler/platform qualification remains below.
- [x] F02-02: Support explicit JMH blackhole arguments with no per-operation boxing/allocation.
  Evidence: mixed/repeated argument identity, bytecode overload/allocation checks, native explicit-sink fixture.
- [ ] F02-03: Prove the expected intrinsic was installed for each qualified compiler profile.
  Local final-LIR matrix and deliberately disabled-intrinsic control pass; other profiles remain open.
- [ ] F02-04: Define compiler data/control/memory ordering at every measurement border.
- [ ] F02-05: Validate runtime/CPU ordering separately from compiler ordering on amd64 and arm64.
- [ ] F02-06: Add positive/negative kernels for DCE, CSE, constant folding, hoisting, sinking,
  loop elimination, scalar replacement, escape changes, and speculative optimization.
- [ ] F02-07: Archive bytecode, Graal graphs/IR, symbols, disassembly, and compiler options per proof case.
- [ ] F02-08: Cross-check structural evidence with dynamic counters and deliberate broken controls.
- [ ] F02-09: Exercise O0/O1/O2/O3, PGO instrument/use, monolithic/layered, AOT/JIT combinations.
- [ ] F02-10: Qualify void benchmarks, exceptions, allocation, shared state, and invocation fixtures.
- [ ] F02-11: Bind qualification results to exact compiler/runtime/helper binary identities.
- [ ] F02-12: Fail strict measurements when required evidence is missing; keep exploratory mode explicitly labeled.

Exit: an accuracy claim identifies its assumptions, exact artifacts, architecture,
and supporting tests. A volatile write, function name, passing unit test, or high
coverage percentage alone is not a compiler/CPU ordering proof.

## M3 — native instrumentation, Myna, and simulation

- [ ] F03-01: Design a small versioned C ABI for regions, blackhole helpers, counters, and capabilities.
- [x] F03-02: Generate and validate bindings using Myna; avoid manually drifting C/Rust/SVM declarations.
  Evidence: pinned generator/descriptor, six generated targets, regeneration drift checks,
  six-target Java compilation and C/LLVM lowering, actual SVM header comparison and Linux run.
  Windows adaptation is restricted to SMH's integer/pointer ABI; see docs/FFI.md.
- [ ] F03-03: Test ownership, nulls, lengths, status codes, teardown, thread attachment, and ABI mismatch rejection.
  Initial bootstrap ownership/null/status/teardown/mismatch tests pass; broader region/attachment ABI remains open.
- [ ] F03-04: Implement Callgrind region requests, deterministic work budgets, and per-case event dumps.
  Function-selected regions, deterministic budgets, and per-case dumps work locally for
  monolithic/layered O2. Native client-request control and online simulation convergence remain open.
- [x] F03-05: Parse instruction/cache/branch counters with exact event names and integer precision.
  Evidence: CallgrindDump malformed/contradictory-input tests, counts beyond 64 bits,
  exact ratios, and actual 13-event Callgrind imports without time-unit substitution.
- [ ] F03-06: Provide a Codspeed adapter using its supported integration ABI and explicit simulation/wall modes.
  Partial evidence: `tools/test_codspeed.py` exercises CodSpeed 5.6.3 exec-harness
  simulation and walltime against the nested Elide application benchmark. This measures
  the whole native process; custom region ABI integration and score import remain open.
- [ ] F03-07: Exclude setup, warmup, reporting, and unrelated threads from the selected measurement region.
  Current Callgrind fixture controls pass on Linux amd64; other backends/hosts remain open.
- [x] F03-08: Publish simulator version, cache model, architecture, invocation count, and raw files.
  Evidence: actual results.json, command/config/binary hashes, raw dumps and per-file hashes;
  build-sidecar attribution is explicitly unverified, instrumented PGO is labeled separately.
- [ ] F03-09: Report backend/host capability differences; never substitute wall time for missing simulation.
- [x] F03-10: Validate instruction-count repeatability and deliberately perturbed region boundaries.
  Evidence: tools/test_simulation.py and local regions-v2 evidence for O2 monolithic/layered
  repeatability, warmup/fixture perturbations, affine work scaling, disabled/unbounded controls.
- [ ] F03-11: Support native simulation on every available platform; document precisely where an upstream
  backend needs a Linux worker or has no supported implementation.

Exit: real simulator runs and broken-border controls, not a dummy counter or a
renamed wall-clock result. Functional support and backend availability are separate.

## M4 — PGO, image layers, and artifact lifecycle

- [x] F04-01: Build and execute baseline O1/O2/O3 layered AOT variants on Linux amd64.
  Evidence: initial native integration logs in `build/native-tests/` (local, not published qualification).
- [x] F04-02: Exercise a full-image instrument/train/profile-use cycle locally.
- [ ] F04-03: Model training datasets, seeds, run settings, profiles, and benchmark identities explicitly.
- [ ] F04-04: Exclude instrumented runs from final comparisons and report PGO provenance.
- [ ] F04-05: Probe exact fork/version capabilities, including layered PGO; do not hardcode upstream assumptions.
- [ ] F04-06: Isolate cache variants by optimization, profile contents, compiler, GC, target ISA, ABI, and sanitizers.
  Partial evidence: `smh bench` hashes source/dependency/SDK/toolchain contents, options,
  profiles, environment, and explicit `cacheInputs`; JVM tests exercise invalidation.
  Undeclared external native dependencies and the full target matrix remain unqualified.
- [ ] F04-07: Lock concurrent producers; publish complete artifacts atomically; detect stale/corrupt caches.
  Partial evidence: local executable-cache entries use per-output file locks, immutable
  generations, atomic receipts, and artifact checksums; failed or changing-input builds
  do not publish entries. Shared base-layer producers across different output roots
  still need locking and concurrent native stress evidence.
- [ ] F04-08: Make cached layers relocatable where the compiler permits and test deployed search paths.
- [ ] F04-09: Capture effective options and executable/library/profile digests in every report.
- [ ] F04-10: Add profile compatibility checks and cross-platform PGO qualification.
- [ ] F04-11: Expose reproducible train/build/run/compare commands and JSON configuration.

## M5 — platform and runtime qualification

Each cell needs build, functional, clock, FFI, borders/blackholes, deployment,
sanitizer applicability, and native measurement evidence. Compiling on another
architecture or running under emulation does not qualify native timing.

| OS | amd64 | arm64 |
| --- | --- | --- |
| Linux | Initial local AOT functional evidence; accuracy qualification open | Open |
| macOS | Open; verify Elide/Graal/toolchain artifact availability independently | Open |
| Windows | Open | Open; verify compiler/runtime distribution availability |

- [ ] F05-01: Centralize executable/shared-library names, path separators, loader search paths, and process handling.
- [ ] F05-02: Support Windows DLL search/ownership, MSVC/clang-cl, argument encoding, and clean process teardown.
- [ ] F05-03: Support macOS loader paths, signing/quarantine requirements, and Apple/Intel clock behavior.
- [ ] F05-04: Qualify native monotonic clocks, timer frequency, affinity, heterogeneous cores, and frequency scaling.
- [ ] F05-05: Capture CPU topology/features, OS/kernel, virtualization, power policy, affinity, and contention.
- [ ] F05-06: Use real amd64 and arm64 workers, including independent reference hardware measurements.
- [ ] F05-07: Distinguish portable JVM support, Elide build-host support, and native compiler support.
- [ ] F05-08: Add JVM/Graal JIT and Crema execution without changing benchmark semantics or result meaning.
- [ ] F05-09: Add Truffle guest runtimes after host-side measurement contracts are qualified.

## M6 — testing, fuzzing, sanitizers, and proof discipline

- [ ] F06-01: Replace subprocess-only coverage with in-process tests of every production Java component.
- [ ] F06-02: Test the processor through compiler diagnostics, generated source, execution, and malformed inputs.
- [ ] F06-03: Differential-test statistics/units against an independent reference implementation.
- [ ] F06-04: Property-test invariants, boundary values, permutation/scale invariance, and stop bounds.
- [ ] F06-05: Fuzz JSON/configuration, CLI arguments, benchmark metadata, state graphs, result parsing, and FFI.
  Pinned Jazzer now fuzzes Callgrind summary parsing and exact-counter construction, with
  retained corpus and a required broken-target finding/replay. A local 100,000-input campaign
  covered all 55 parser lines and 48 branches. Run-configuration fuzzing also passes 100,000
  inputs with strict field types and independent exact-value oracles. CLI, build configuration,
  state graphs and FFI targets remain open; see FUZZING.md.
- [ ] F06-06: Retain minimized crash corpora and deterministic regression seeds; use bounded PR and longer scheduled runs.
- [ ] F06-07: Run Rust Miri where the ABI harness permits, Clippy, cargo-audit/deny, and memory/UB tests.
  Seven compatible Rust tests pass under strict provenance, both borrow models and two seeds,
  interpreted for Linux amd64 and arm64; each configuration detects a use-after-free control.
  Real dynamic-loader checks remain native tests. cargo-audit/deny and wider profiles remain open.
- [ ] F06-08: Use elide-dev/toolchain ASan/UBSan/LSan, TSan, and applicable MSan/HWASan profiles.
- [ ] F06-09: Use elide-dev/graal sanitizer/coverage profiles where the fork actually instruments the tested code.
- [ ] F06-10: Verify sanitizer installations with known-bug negative controls before accepting a clean run.
  Rust ASan/LSan now require heap-buffer-overflow, use-after-free, and leak detection
  through dynamically loaded FFI providers before running eleven instrumented tests. Compiler identity, hashes, logs and JUnit
  are retained; Linux amd64/arm64 hosted checks pass.
  Other instrumentors remain open; see SANITIZERS.md.
- [ ] F06-11: Keep native helper, JNI/FFI, Rust, SVM-managed heap, and JIT-generated code coverage claims separate.
- [ ] F06-12: Run mutation tests to show tests reject damaged timing, units, sink, and border logic.
- [ ] F06-13: Specify the measurement state machine and mechanically check ordering/termination invariants.
- [ ] F06-14: Require linked evidence and review for changes affecting timing, compiler intrinsics, FFI, or statistics.

## M7 — results, UX, and ecosystem integration

- [ ] F07-01: Version report schema and include raw observations, sample order, stopping rules, and reproducibility metadata.
- [ ] F07-02: Separate validity, statistical stability, JIT quiescence, precision, and environmental qualification.
- [ ] F07-03: Make unstable, unqualified, failed, instrumented, and incomparable results unmistakable in CLI and JSON.
- [ ] F07-04: Provide intervals/effect sizes using methods suitable for independent forks and adaptive sampling.
- [ ] F07-05: Preserve all data and predeclared analysis rules; do not silently remove outliers.
- [ ] F07-06: Add paired comparisons and regression decisions with practical effect thresholds.
- [x] F07-07: Use Rainbowgum for diagnostics on stderr, outside measured regions; keep stdout machine-readable.
  Evidence: native Micronaut help/build/run checks, CLI/JVM subprocess stdout checks; benchmark runtime excludes logging.
- [ ] F07-08: Finish JMH-compatible exports and dedicated Bencher/Codspeed/Criterion adapters with golden fixtures.
- [ ] F07-09: Provide reproducible examples, troubleshooting, report explanations, and a published capability matrix.
  Partial evidence: the nested invoice application is independently built with Elide and
  benchmarked from its JAR; `tools/test_invoice.py` validates both parameter cases.
- [x] F07-10: Provide animated terminal progress and visual result summaries with plain/JSON fallback.
  Accepted design: make the iteration-score histogram the main result, with readable
  rounded scores, numeric axes, and restrained semantic color. At wide terminal sizes,
  show run order beside the distribution; include every observation in the histogram
  and contiguous min/max trace columns. Baseline comparisons use shared, centered
  change bars with signed deltas; Callgrind uses event counts and paired bars on a
  shared zero-based scale. Preserve measurement limits and qualification labels while
  keeping methodology and exact values in the raw report.
  Evidence: 115 passing JVM tests, including fixtures for bin counts, constant
  and extreme samples, width adaptation, units, descriptive comparisons, and simulation
  identity; passing real native PTYs at 58/100/140 columns for streaming, color,
  retained observations, redirected JSON, and cursor restoration on SIGINT.
  Real upstream Callgrind 3.25.1 runs verify single/comparison views and redirected
  JSON with the rebuilt native CLI. Production coverage remains above 99% for lines
  and branches; the mathematical kernel remains at 100%. Replay recordings, rendered
  previews, commands, and artifact hashes are retained under `build/terminal-tests/`;
  scope and limits are recorded in `docs/ASSURANCE.md`.

## M8 — security, releases, and OpenSSF

- [ ] F08-01: Document security reporting, threat model, supported versions, governance, reviews, and contribution rules.
- [x] F08-02: Add Scorecard, dependency review, CodeQL/static analysis, dependency updates, and secret scanning.
  Evidence: hosted runs and repository settings in `docs/ASSURANCE.md`.
- [ ] F08-03: Use least-privilege permissions, pinned actions, protected CI trust boundaries, and harden-runner.
- [ ] F08-04: Produce reproducible archives, checksums, SBOMs, signed provenance, and verified release artifacts.
- [ ] F08-05: Enforce two-person review/branch protection and verify organization 2FA/access policies (hosting evidence).
- [ ] F08-06: Establish multiple maintainers and independent significant contributors (human/project evidence).
- [ ] F08-07: Complete Passing, Silver, and Gold criterion-by-criterion self-assessments with public evidence.
- [ ] F08-08: Register/submit the badge and publish real measured Scorecard results; never pre-award a badge.
- [ ] F08-09: Conduct and publish a security review, address findings, and maintain response/release evidence.

## Working order and definition of done

Start with M0, then the deterministic M1 kernel, M2 sinks/borders, and M3 real
simulation. PGO, platform portability, CI, and fuzzing are developed alongside
their dependent components. Each implementation change includes focused tests,
measured coverage, documentation, and an updated assurance record. A milestone
cannot close by lowering thresholds, hiding unsupported platforms, excluding
production code from coverage, or replacing actual tooling with a passing stub.

External account/repository controls and hardware qualification remain open until
their real evidence exists. The overall goal is unfinished until the applicable
items above and their acceptance evidence are complete.
