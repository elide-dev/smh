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
| JIT | Explicit `jitQuiescence: unknown`; runtime-loaded Crema/Ristretto and HotSpot/C2 validation records benchmark compilation diagnostics | Compilation/deoptimization event stabilization, sink qualification, and cross-runtime accuracy |

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

## Elide application, CodSpeed, and terminal evidence

The initial 2026-11-04 local integration campaign built `examples/invoice-app` with pinned
Elide, packaged its application JAR, then compiled a separate SMH benchmark against
that JAR with Oracle GraalVM 25.3.4.1 / JDK 25.0.4.1 on Linux amd64 (WSL2). Both
input sizes ran natively with five wall-time observations each. The application CLI
returned the independently checked total of 6497 cents.

`tools/test_codspeed.py` passed with CodSpeed CLI 5.6.3 and exec-harness 1.4.1. The
fixed-work native process produced 3,792,832 simulated instructions in its named
exec-harness region and three positive walltime rounds. Evidence, exact commands,
config/binary hashes, compiler identity, host, and raw instrument artifacts are in
`build/codspeed-tests/20261105T023039611942Z/`; application evidence is in
`build/invoice-tests/`. The verifier rejects absent, zero, unrelated, duplicate,
and malformed simulated regions. These local records are not hosted attestations.

This uses CodSpeed's **whole-process exec harness**, including startup, fixtures,
100 invocations, and reporting. It does not implement the CodSpeed region ABI or
import CodSpeed measurements into SMH per-operation scores. SMH's fixed-work
stdout remains a pending Callgrind manifest. Walltime profiling was explicitly
disabled (`CODSPEED_PERF_ENABLED=false`); the timings are real but this shared host
is not qualified for low-noise regression decisions. No results were uploaded.

The initial debug-enabled build failed in the CodSpeed Valgrind DWARF reader
(`get_Form_contents`, assertion `td3`). That failure is retained under
`build/codspeed-tests/20261105T022006293723Z/`. The working sample omits `-g` and
retains function symbols; Java source-line profiling remains unqualified. This
workaround is explicit in its user guide and does not qualify debug-enabled images.

Terminal rendering runs only in the parent CLI; it adds no instrumentation to the
measured region. Its CPU scheduling can still perturb wall-time observations.
Baseline percentages are descriptive score ratios matched by benchmark, mode,
parameters, and units; they do not establish statistical significance, compiler
identity, or equivalent measurement environments. Raw reports are retained.
The initial terminal JVM suite passed 84 tests, with production coverage 1662/1670 lines
(99.52%) and 984/991 branches (99.29%); the mathematical kernel remains 100%.
The native `mise run test-terminal` check passed: moving 24-bit frames,
matched deltas against a real earlier run, raw JSON retention, redirected output,
and SIGINT cursor restoration. Replay recordings are in `build/terminal-tests/`. The new GitHub integration workflow has not yet executed
in hosted CI; its configuration is not passing evidence.

## Cached builds and the initial terminal layout (historical)

That iteration of the JVM suite passed 94 tests with no skips: production coverage is
1906/1915 lines (99.53%) and 1114/1125 branches (99.02%); mathematical-kernel
coverage remains 100%. Cache tests cover byte changes with preserved timestamps,
compiler implementation/dependency changes, corrupted or missing artifacts, invalid
receipts, forced rebuilds, failed compilation, and inputs changing during compilation.
CLI tests confirm a cache hit still launches a new process. A regression test
separates Elide’s changing temporary directory from real compiler environment options. These tests do not
qualify concurrent native producers across different output directories.

Presentation tests at that point checked ordered bin means using all 1,000 observations, empirical
nearest-rank percentiles, median, warmup stop reasons, and operation totals beyond
signed 64-bit range. The underlying timing/statistics kernel was not changed.
The former warmup and measurement curves were independently scaled, descriptive post-run
summaries; neither curve proves convergence, JIT quiescence, or measurement accuracy.
The sample's fixed counts are 100 warmup and 1,000 measurement iterations per case,
with a 1 ms target. Short iteration targets and this shared host remain limitations.

The native follow-up passed on the same Linux amd64/GraalVM host. `test-invoice`
verified deterministic application JARs, both input sizes, a fresh cached build,
and a verified cache hit with 100 warmup/1,000 measurement observations per case.
Two actual `elide run bench` invocations using the native CLI also passed; the
second reused key prefix `9b5a506cc50c`. Commands and raw reports are retained in
`build/invoice-tests/elide-command.json`, `elide-first.json`, and `elide-hit.json`,
with separate diagnostics. The PTY check passed with both disabled and enabled
warmup, the 1,000-sample summary, true color, deltas, raw JSON, and SIGINT restoration;
recordings and a rendered preview are in `build/terminal-tests/`.

CodSpeed was rerun against the newly built sample: 3,784,709 simulated instructions
and three real walltime rounds passed. Exact identities and artifacts are retained
in `build/codspeed-tests/20261105T031058767555Z/`. The whole-process scope, disabled
walltime profiling, debug-info limitation, local-only status, and lack of hosted
CI evidence described above still apply.

## Streaming presentation refinement (historical)

That presentation removed warmup/measurement sparklines and adds aligned
iteration-score columns, a p10–p90 distribution whisker with median marker,
relative baseline bars, and a logarithmic duration scale when no matching baseline
exists. VM/JDK information is deduplicated into one closing footer. Duration
references are mathematical time scales, not measured hardware latencies. No JIT
curve or JIT detection feature was added.

Completed trials are published after teardown and result construction, outside all
measured regions. The parent renders each published case while the remaining cases
run, without changing the final JSON report. Atomic publication prevents partially
written rows from being rendered. Failed/interrupted runs retain published case
files. Older executables without the reporting channel fall back to their final
report. Reporting I/O and parent activity can still affect subsequent work on the
shared host; this change does not qualify measurement accuracy.

The JVM suite passes 98 tests, including a child/parent handshake that prevents the
child from finishing until its first case is visibly rendered, duplicate-environment
checks, relative-path handling, distribution/scale checks, and failure retention.
Production coverage is 1983/1992 lines (99.55%) and 1172/1183 branches (99.07%);
the mathematical kernel remains 100%. Timing, operation counting, and lifecycle
boundaries were not changed. Lint also passes.

The native follow-up passed on Linux amd64/WSL2 with the pinned Elide and GraalVM
toolchain. `test-invoice` rebuilt the application/benchmark and verified both input
sizes plus build-cache reuse. `test-terminal` verified streamed cards, a single
VM/JDK footer, relative report paths, true color, baseline bars, JSON preservation,
and SIGINT cursor restoration. In the recorded 1,000-sample run, the first case
rendered at 1.135 s and the process completed at 2.285 s: the first card was visible
1.150 s before completion. Recordings, rendered comparison/reference previews, and
`streaming.json` are retained under `build/terminal-tests/`.

CodSpeed simulation and three walltime rounds also passed for the rebuilt sample;
the whole-process simulated total was 4,244,908 instructions. Raw artifacts and
identities are in `build/codspeed-tests/20261105T033555596537Z/`. This remains local
whole-process evidence, without uploads, source-line profiling qualification, or
hosted CI claims.

## Compact terminal presentation (historical)

That presentation replaced distribution whiskers and the wide logarithmic
scale with a prominent mean, a nearby 1/2/5 duration ruler (or baseline bars), and
aligned median, p99, and CV columns. Terminal microseconds use µs; JSON units stay
unchanged. Unassessed phases are omitted, successful stability is quiet, and
non-success stopping reasons remain visible. There is no inferred JIT curve.
Full observations and phase metadata remain in raw reports.

The JVM suite passes 99 tests. Production coverage is 1940/1949 lines (99.54%)
and 1166/1177 branches (99.07%); the mathematical kernel remains 100%.
Lint and all 28 tooling tests pass. This pass changes presentation only; it does
not alter measured work, timing, or the runtime publication protocol.

The rebuilt native CLI passed the real PTY checks on the same Linux amd64/WSL2
host: true color, 1,000-sample results, baseline deltas, µs display, one environment
footer, raw JSON preservation, and SIGINT restoration. In this run the first case
appeared at 1.136 s and completion at 2.262 s (1.126 s of live lead).
Recordings, rendered captures, and timings are in `build/terminal-tests/`.
CodSpeed was not rerun for this presentation-only pass; its preceding evidence
and limitations remain unchanged.

## Visual terminal results

The 2026-11-05 presentation uses all finite iteration scores in equal-width
histogram bins, with count and score axes. Wide terminals add contiguous-column
min/max envelopes that retain isolated spikes. Rounded means, median, p99, and
iteration counts describe the observations; they do not establish individual-operation
latency, confidence, stability, or JIT quiescence. Tiny occupied histogram bins remain
visible at terminal-cell resolution. Nonfinite omissions and non-success stopping
reasons are explicit, and raw observations are unchanged.

Wall comparisons stream rows on a shared ±20% scale, with arrows for larger changes.
Their identity match does not verify host/build/batch equivalence or significance.
Callgrind views normalize exact counters before display, check model/work identity
before comparing, and use zero-based event bars. The standalone simulator trace
shows grouped peaks, not the wall-time view's min/max envelopes. All ordinary
results remain accuracy-unqualified. Presentation stays in the parent process;
its activity can still affect later measurements on the same machine.

The final JVM suite passes 115 tests with no skips. Production coverage is
2461/2470 lines (99.64%) and 1457/1469 branches (99.18%); the mathematical kernel
remains 100% for both. Lint, all 28 tooling tests, and the Rust line-coverage gate
also pass. These changes do not alter measurement regions, operation counting,
the runtime publication protocol, or the Callgrind importer.

The final native CLI was rebuilt with GraalVM Native Image 25.0.4.1 at O2 on
Linux amd64/WSL2. `mise run test-terminal` passes at 58, 100, and 140 columns against
the existing native invoice benchmark. Checks cover streamed distributions and
comparisons, true color, 1,000 retained iterations per case, raw JSON preservation,
automatic plain output, and cursor restoration after SIGINT. SVG/PNG previews are
rendered from the actual terminal recordings, not fabricated benchmark values.

Additional native CLI runs with explicit `--valgrind /usr/bin/valgrind` (upstream
3.25.1) pass for standalone and baseline-comparison displays, plus plain and forced-TUI
redirected JSON. They reuse the existing monolithic simulation fixture, with two
parameter cases and three measured iterations of 100 operations each. This is
local presentation/integration evidence, not new compiler or simulator qualification.
The earlier PATH-selected CodSpeed fork, 3.26.0.codspeed12, emitted additional
thread dumps rejected by the unchanged importer; its failure evidence remains in
`build/terminal-tests/simulation-visual-hf88k2di/`. No fallback or importer relaxation
was introduced.

Commands, revision and working-tree source hashes, tool/host identity, binary hashes,
and artifact locations are recorded in `build/terminal-tests/visual-evidence.json`.
Simulation commands and result directories are in `simulation-evidence.json` beside
the `.cast`, `.svg`, and `.png` captures. The previously recorded CodSpeed whole-process
checks were not rerun for this presentation change.

## Release-candidate portability follow-up

Hosted validation of the visual-results candidate found a Unix-only path assertion
and missing Windows execution of the new cache/bench tests. Those tests now use
native command fixtures on Windows, and uncovered a case-sensitive environment-map
lookup that missed Windows' `Path`. Tool lookup now uses Java's platform-aware
single-variable environment lookup. Executable search also has explicit coverage
for a non-executable file preceding a usable tool.

Cache locking remains scoped to the complete lookup/build/publication operation.
The owning file closes its channel and releases the lock on success and failure;
existing failure/rebuild tests exercise subsequent acquisition. This does not add
cross-process stress qualification. Both chart panels have equal height, so their
join no longer contains an unreachable shorter-panel branch.

The follow-up local suite passes 116 tests with no skips: 2466/2468 production
lines (99.92%), 1456/1463 branches (99.52%), and the mathematical kernel remains
100%. The CLI logging policy has a direct test for suppressed INFO and retained
WARNING/ERROR routes. Hosted results remain tracked on PR #10; local test results
are not a substitute for that platform matrix.

Windows portable workers also exposed a sharing violation when deleting redirected
files immediately after interruption. Failed/interrupted terminal runs now retain
both raw output and diagnostics, report their paths, terminate the child, and restore
the cursor. Tests require the original interruption and retained files. Successful
redirected runs still remove temporary output after copying it.

CodSpeed 5.6.3 adds a banner and GitHub log-group markers to stdout in hosted CI.
The integration evidence reader now requires one JSON manifest inside that log,
rejects missing/duplicate/malformed manifests, and retains the complete original log.
The retained hosted sample validates at 4,136,855 whole-process simulated instructions;
this is not a per-operation score. The revised local checks pass 116 JVM tests,
29 tooling tests, lint, and real CodSpeed simulation plus three walltime rounds using
the existing native invoice fixture. CodSpeed artifacts are retained under
`build/codspeed-tests/20261105T160344324394Z/`. Final local production coverage is
2468/2470 lines (99.92%) and 1460/1467 branches (99.52%); the math kernel remains 100%.
Hosted validation must still pass for the amended candidate.

## Runtime-loaded execution and build reuse (2026-11-05)

Local Linux amd64 validation used Oracle GraalVM 25.3.4.1+1.1 / JDK 25.0.4.1.
`tools/test_execution.py` compiles a stateful benchmark, checks cold/unchanged/
changed-source bytecode caching, and executes the same generated workload on
HotSpot/C2 and the native Crema/Ristretto host. C2 evidence is an XML `nmethod`
installation with `compiler='c2'`; Crema evidence is a Ristretto code-map entry
for the benchmark method. Raw diagnostics, result JSON, build/run configuration,
artifact identities, and scoped timings remain under `build/execution-tests/`.
The verified Crema host SHA-256 is
`96a00f2681e8dbd18e4439329faf6327e6c7ac8826e663d7df8a7724a7c216ce`.
Its bundle contains the executable and eleven companion native libraries;
loading checks the native inventory and SDK dependency digests before execution.

The host preserves `java.base`, the SMH runtime API, and JMH's blackhole API.
Initial negative runs without preservation failed on runtime allocation of
`BenchmarkSpec` and an unreachable `List.of` overload. Their diagnostics were
retained locally; successful timing output was not accepted as a substitute for
resolving those failures. The payload loader stays alive through teardown and
restores the thread context loader on success and failure.

`tools/test_layers.py` produced local evidence under
`build/layer-tests/run-q8cqcbor/`: two concurrent producers with different output
roots compiled one base layer; a relocated SDK reused that layer for changed
benchmark builds; a deployed executable ran with both original SDK and image
directories hidden. Layer identity was
`d5f99cf6c026db248e45bc42e90de71f97f6913cb3d0c4da354b0c72e748916d`.
Unit tests cover corrupted/incomplete layer receipts, failed publication,
bytecode invalidation, incompatible host manifests, and restoration after failure.
Release bundle tests reject mismatched hosts and corrupt companion libraries.

These are functional and compilation observations, not cross-runtime accuracy or
blackhole qualification. C2 can optimize the harness together with workload code;
Crema crosses the AOT-host/runtime-loaded adapter boundary. Runtime-loaded results
retain `jitCompilation: unverified` and `jitQuiescence: unknown`; external
validation logs do not turn every later run into a qualified one. Diagnostic and
training results are excluded from score deltas. JIT-event
stabilization, additional JDK modules, and the broader platform matrix remain
open. Cache checks detect changed inputs before publication but do not freeze
external files during compilation or execution. Two concurrent producers are not crash-stress
or distributed-filesystem qualification. Local build timings include their stated
preparation/process scope and are not portable latency guarantees.

## Delegated JMH and Leyden

`smh jmh` delegates to JMH 1.37's own annotation processor and generated loops;
SMH does not implement JMH's timer or sinks. The packaged `smh-jmh.jar` supplies
a small JVM-only launcher. Fixed options and exact JVM argument arrays override
fork annotations; one worker and positive fresh measurement forks are explicit.
Original primary/secondary metrics, confidence intervals, and nested fork/iteration
observations remain unchanged in `jmh-results.json`. The adapted `results.json`
adds harness/runtime/cache identity and display metadata. Tests check metric
preservation, parameter identity, histograms, fork-order labels, and descriptive
cross-harness deltas. No accuracy or JIT-quiescence qualification is inferred.

Local Linux amd64 integration with Oracle GraalVM JDK 25.0.4.1 exercised actual
JMH tiered C1/C2 and C2-only forks, two parameter cases, two forks per case and
three measurement iterations. `tools/test_jmh.py --java-home /usr/lib/jvm/gvm.jdk25`
retains commands, logs, raw/adapted JSON and artifact hashes under
`build/jmh-tests/run-*/`. It checks Leyden creation, cache reuse, source-content
invalidation, cross-harness rendering, and nonzero exit/no adapted report after
a deliberate benchmark exception. These short fixtures establish orchestration
behavior, not a speed advantage or a representative warmup duration.
The extracted native distribution was also exercised from an installation path
containing spaces: the complete JMH/Leyden suite passed through its native CLI,
then packaged Crema and a layered Native Image of the same workload rendered
comparisons against the retained JMH baseline. Archive checksums and native CLI
identity are retained in `build/jmh-tests/package with spaces-*/verification.json`.
This is local packaging/integration evidence, not hosted release evidence.

Leyden uses JDK 25 `AOTCacheOutput` in a separate nonforked training process.
Training files have a training identity and are never adapted as measurement
results. JMH's fork-only compiler hints and blackholes can differ in training;
these profiles are not claimed equivalent to profiles from measured forks.
Measurement requires `AOTMode=on` and the cache path. Each fork must log opening
the expected cache and an enabled full module graph, without AOT errors. Initial
experiments exposed a JVMCI module-graph mismatch despite a successful JMH exit;
explicitly disabling JVMCI in both training and measurement fixed it. Degraded
loading is now rejected before adapted results are published. Log checks are
conservative and tied to the exercised JVM format. They establish archive use,
not that every benchmark class or method profile was restored.

Unit tests inject corrupt archives, missing artifacts, failed training, missing
fork-loading evidence, and AOT error logs. Those fixtures are control-flow proof,
not JVM evidence. Published caches reuse the existing locked/hash-verified atomic
artifact cache. Keys retain build and training identity, runtime flags, JDK and
JAR hashes. External files are not frozen while executing; undeclared files read
by a workload or a flag remain outside the cache contract. Cache artifacts are
local to an installation and are not shipped as portable performance profiles.

Parent cleanup terminates observed descendant processes before the parent and
allows a bounded interval for JVM child reaping; a real parent/child JVM test
covers this path. This does not qualify arbitrary detached processes or a child
that creates a new process concurrently with termination. The terminal renderer
and result adaptation run outside the measured JVM loops. AOT startup logging is
retained; no JVM cold-start metric or steady-state speed ranking is claimed.
The release workflow includes the integration check; local execution does not
establish a hosted workflow pass or a published release.

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

## Startup and soak experiments

The [experiment contract](EXPERIMENTS.md) separates parent-observed wall startup
from guest-process simulation and duration-based soak from exact-work soak.
Startup records parent launch IDs, launch-to-notification (or exit), completion,
and every successful observation. A later cleanup failure invalidates the
launch. Soak retains completed windows in an append-only diagnostic journal;
failed or interrupted journals are not successful result reports. Warmup stopping
conditions remain visible even when soak completes its measurement budget.

JVM tests exercise independent clock traces, persistent trial state, logical
operation normalization, partial final windows, lifecycle failures, duplicate or
missing readiness notifications, timeouts, malformed simulator dumps, comparison
identity, and charts from 40 to 160 columns. The final JVM suite passes all
163 tests with no skips: production line coverage is 4,221/4,235 (99.67%), branch
coverage is 2,491/2,515 (99.05%), and mathematical-kernel line and branch coverage
are 100%. Current coverage is retained under `build/variant-validation/`. Earlier
mode validation receipts are retained under `build/experiment-validation/run-20261105/`; individual
runs, commands, artifact hashes, raw counters, and failure controls are under
`build/experiment-tests/`. The receipt distinguishes final checks from earlier
failed probes. Terminal examples in the guide are short validation captures,
not cross-engine performance evidence.

Real Linux AMD64 checks use JDK/Native Image 25.0.4.1 (Oracle GraalVM
25.3.4.1+1.1), the rebuilt Crema host with runtime class loading and Ristretto,
and upstream Callgrind 3.25.1. They exercise fresh processes on AOT, HotSpot,
C2, and Crema; explicit readiness before the remainder of setup; first result
before deliberate cleanup delay; process exit; full wall-duration budgets; and
exact simulator budgets ending in a partial window. Native terminal recordings
cover 58- and 140-column layouts. Simulation checks cover monolithic and layered
images and verify the preserved `Startup.reached()` snapshot boundary. The
new CI steps retain these observations; local evidence is not a hosted CI pass.

The [Callgrind format specification](https://valgrind.org/docs/manual/cl-format.html)
permits `summary` to exceed attributed `totals`. An actual post-endpoint snapshot
contained 271,172 summary instructions and 271,167 attributed instructions.
Process-wide startup import retains both, uses the full summary for scoring,
and rejects attribution counts above the summary. Ordinary benchmark-region
import still requires exact agreement. All guest threads contribute to startup;
parent and kernel work do not. No simulated event count is presented as wall time.

This compiler rejected a layered fixture calling `ProcessHandle.current()` because
`LinkedTransferQueueDualNodeIsUniprocessorAccessor` became reachable only in the
application layer. Moving PID observation to the parent removes that diagnostic
API from the workload; the layered fixture then builds with the cached base.
This does not qualify arbitrary JDK API reachability across layers. Workloads
hitting this compiler restriction can explicitly set `layers: false`; SMH does
not silently change build models.

Fresh processes do not establish cold filesystem caches, JIT quiescence, or
accuracy qualification. Validation jobs may run concurrently and their scores
must not be used to rank engines. Startup wall timing includes notification and
parent scheduling; simulation snapshots precede notification. Soak's elapsed
budget includes fixtures and between-window harness work, while window scores
retain the existing invocation/batch scope. Partial simulator windows include
fixed loop/call overhead, which can affect their per-operation scores. JMH and
Leyden delegation remain steady-only; runtime-loaded hosts remain wall-only.

The compact report follow-up retains the same measurement boundaries and raw
qualification fields. Its JVM tests exercise temporary startup progress before
report rendering, build-log routing, narrow layouts, preserved histogram tails,
and isolated timeline spikes at four plot rows. `build/clean-ui-validation/`
retains the native invoice-app terminal captures and current validation receipt.

CLI failure reporting is tested through the real Micronaut/Picocli entry point
and the native CLI. Error records retain nonzero exit status, original details,
and recovery advice; full cause chains remain in diagnostic files. Tests cover
failed log writes without masking the original failure, terminal control-character
sanitization, plain/colored output, and native compiler-log retention with explicit
verbose replay. Local evidence is under `build/error-validation/` and
`build/terminal-tests/`; no hosted CI pass is implied.

## Interleaved variants and live reports

Startup scheduling now runs one process per case per round, rotating the first
case each round. Each observation retains its round and slot, raw timing and PID;
per-launch files and `partial-results.json` survive failure. Command candidates
reuse the parent-side process launcher and its timeout/cleanup path, with exit as
the only supported endpoint and wall time as the only supported backend. The
measurement includes process launch and exit detection overhead. The command's
own compilation, fixture work and teardown are included. Filesystem caches and
host state remain uncontrolled; rotating order does not establish statistical
independence or eliminate carryover effects.

`VariantChartsTest` checks a prescribed two-case, three-round schedule against an
independent expected order, checks snapshot persistence before the next launch,
and exercises shared-scale grouping, overlap markers, constant and invalid
series, more than five variants, and narrow legends. Incompatible endpoints,
backends, execution identities and simulator models remain separate groups.
`TerminalDisplayTest` uses a child waiting for a display acknowledgement to check
that live snapshots arrive before child exit. The final report is rendered after
restoring the transient terminal screen. No report rendering runs inside a
measured invocation or active startup child.

The winner label is explicitly **fastest observed**, based on separate arithmetic
means. No confidence interval, multiplicity adjustment, practical effect threshold
or significance decision is implemented. F07-06 remains open. Parameter-size
variants receive no winner label. Histograms and histories share axes without
pooling the underlying samples. Large live reports are clipped to the viewport;
the final report and raw data retain every case.

Final verification for this change: all 163 JVM tests pass without skips, with
4,221/4,235 production lines (99.67%), 2,491/2,515 branches (99.05%), and 100% of
the mathematical kernel covered. Lint and all 31 Python tool tests pass. The
native CLI was rebuilt on Linux AMD64 with Native Image 25.0.4.1; real PTY runs
verify command candidates and invoice startup at 40/58/100/140 columns, rotated
round positions, distinct invoice launch PIDs, retained samples, clean final
reports, redirected JSON, failed candidates and SIGINT restoration. A separate
100-column capture uses 20 launches per invoice size. Commands, raw reports,
recordings, coverage and artifact hashes are retained in
`build/variant-validation/`, with the broader native terminal recordings under
`build/terminal-tests/`. These are behavior and presentation checks, not evidence
that one workload or execution engine is faster in general.

## Preparation before measurement

Build configurations may declare direct preparation argv. The CLI invokes it once
before classpath validation and content-cache lookup for build, bench, and JMH
jobs. Command comparisons accept repeated preparation commands; every preparation
finishes before the first measured launch. Preparation never runs inside an active
startup measurement, and a failed launch is never replaced by a rebuild and retry.
Incremental compilation belongs to the declared builder; SMH fingerprints its
resulting inputs. Concurrent builders that rewrite the same project inputs are
not serialized by this preparation mechanism. Cache publication still rechecks
input identity after compilation.

Build output is staged outside mutable project outputs. Effective run files are
written after preparation, and completed preparation logs are archived until the
whole group finishes, so clean steps cannot erase earlier evidence. Shutdown
cleanup stops the active build process before publishing its output and argv and
restoring earlier logs. If publication is blocked, retained temporary paths are
reported without replacing the original build failure. Startup failures include
the child exit code and a bounded, sanitized first stderr line; complete launch
diagnostics and failure records remain on disk.

JVM checks cover missing-input repair, unchanged-input reuse, changed-input cache
invalidation, argv/cwd fidelity, dry-run planning, repeated clean steps, failed
preparation, blocked publication, cleanup ordering, and absence of launch retries.
Native checks exercise preparation and interruption through the packaged CLI;
the invoice check removes its JAR before invoking startup benchmarks, then repeats
the run to verify unchanged-input cache reuse. Local commands, logs, coverage,
and artifact hashes are retained under `build/preparation-validation/`, with the
terminal suite's recordings under `build/terminal-tests/`. These checks establish
orchestration behavior, not comparative performance accuracy or a hosted CI pass.

The final JVM run passes all 181 tests without skips: 4,402/4,422 production lines
(99.55%), 2,557/2,581 branches (99.07%), and the entire mathematical kernel
(151 lines, 68 branches). Lint and all 31 Python tool tests pass. Native evidence
uses the rebuilt Linux AMD64 CLI with Native Image 25.0.4.1.
