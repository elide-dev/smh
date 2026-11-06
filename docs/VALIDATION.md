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
mise run native-crema
mise run test-execution
mise run test-layers
```

PGO needs a supporting compiler distribution. Simulation requires Linux and
Valgrind. Qualification fixtures inspect compiler output and use deliberately
broken controls; they do not certify all compiler/CPU measurement boundaries.
Compiler profiles and frozen inputs must remain attached to their evidence.

`test-execution` requires a JDK 25 HotSpot runtime and the matching Crema host
bundle. It records bytecode cache behavior and build timings, and requires an
installed C2 nmethod and a Ristretto code-map entry for the benchmark method.
Pass `--java-home /path/to/jdk25` to `tools/test_execution.py` to select the JVM.
Compilation diagnostics qualify those validation runs as diagnostic; neither
compilation evidence nor timing convergence establishes JIT quiescence.

`test-layers` runs two real native producers against one layer cache, relocates
the SDK, checks changed/unchanged builds, then runs a deployed image with the
original image and SDK directories hidden. Evidence is retained under
`build/execution-tests/` and `build/layer-tests/`. These tests do not replace the
existing compiler, blackhole, simulation, or ABI qualification tasks.

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

## Application, CodSpeed, and terminal integration

```sh
mise run test-invoice
# Requires the real CodSpeed CLI 5.6.3; never uploads.
mise run test-codspeed
# Requires the native SMH CLI and the invoice benchmark built above; POSIX PTY.
mise run native
mise run test-terminal
```

The application test packages an independent Elide project and benchmarks its JAR.
The CodSpeed check requires a named native-process simulation region with positive
instruction totals and three real walltime rounds. It measures the entire fixed-work
process, not SMH's per-operation region. See the [sample guide](../examples/invoice-app/README.md)
for instrument scope and debug-info limits.

The invoice check also requires deterministic application JARs, a fresh `smh bench`
build, and a verified cache hit; both runs retain 100 warmup and 1,000 measurement
observations per case. JVM tests exercise corruption, changed inputs, failed builds,
and aligned distribution summaries. A child-process handshake test requires the
first case to be rendered before the child is allowed to finish.

The terminal check launches the real CLI in pseudo-terminals at 58, 100, and 140
columns, setting both the kernel terminal size and `COLUMNS`. It checks the histogram
and wide run-order chart, moving frames, completed-case streaming, one environment
footer, 24-bit color, and centered comparison bars against a real earlier run.
Retained JSON is compared with the run's configured output, including all raw
observations. Redirected output, automatic plain-mode policy, and SIGINT cursor
restoration are checked separately. Replayable asciinema v2 recordings, including
their actual terminal sizes, are written under `build/terminal-tests/`.
The same native check exercises missing dependencies/configurations, malformed
JSON, missing required options, and real compiler failures. It verifies stable
JSON error records, recovery advice, retained diagnostic/compiler logs, explicit
verbose replay, clean result stdout, terminal color, and `NO_COLOR`.

JVM tests separately cover histogram counts and boundary values, run-order grouping,
rounded presentation without changing raw values, width adaptation, comparison
matching, missing/zero/invalid baselines, simulation event bars, exit codes, and
plain-mode policy. Simulation rendering fixtures establish presentation behavior;
they do not establish hardware accuracy or real Callgrind execution.

## JMH and Leyden

Build the SDK, then run `mise run test-jmh` with a JDK 25 `java` on PATH, or
`mise exec -- python3 tools/test_jmh.py --java-home /path/to/jdk-25`. This exercises
actual JMH annotation processing, two parameter cases and two measurement forks,
uncached tiered HotSpot, C2-only, Leyden training/reuse/source invalidation, and
benchmark failure propagation. Evidence is retained under `build/jmh-tests/`.
Unit tests separately inject malformed results, corrupt caches, missing artifacts,
failed training, and degraded cache-loading logs; those fixtures are not JVM proof.

To verify an extracted distribution, add `--home /path/to/sdk` and
`--cli /path/to/sdk/smh` to `tools/test_jmh.py`. This executes the same checks
through the packaged native CLI and JARs, including installations with spaces.

## Experiment modes

After SDK/JVM packaging, run the fresh-process and sustained-work checks
sequentially with native compilation:

```sh
mise run test-experiments -- --execution hotspot --java-home /path/to/jdk
mise run test-experiments -- --execution hotspot-c2 --java-home /path/to/jdk
mise run native-crema
mise run test-experiments -- --execution crema --java-home /path/to/jdk
mise run test-experiments -- --execution aot --java-home /path/to/graalvm
mise run test-experiments -- --execution aot --simulation --java-home /path/to/graalvm --valgrind /path/to/valgrind
mise run test-experiments -- --execution aot --simulation --layers --java-home /path/to/graalvm --valgrind /path/to/valgrind
```

Use `--cli build/smh` to exercise the packaged native CLI as the parent launcher.
Each invocation keeps configurations, command arguments, stdout JSON, ANSI
charts, and an evidence receipt under `build/experiment-tests/`. These checks
exercise all startup endpoints, distinct process IDs, full soak budgets,
partial final simulation windows, retained observations, and cleanup failures.
Wall checks also place a deliberate delay after the endpoint. They do not
establish cross-engine equivalence or performance accuracy. JVM tests separately
exercise deterministic timing, lifecycle failures, malformed simulator dumps,
comparison identity, and chart widths from 40 to 160 columns.

The native terminal suite also runs direct command candidates at 40, 58, 100 and
140 columns, verifies round/slot ordering and retained timings, checks the final
fastest-observed comparison record, and exercises failure after a live frame has
opened. It checks alternate-screen restoration and absence of transient progress
in the final report. The ordinary invoice runs verify grouped charts, raw sample
retention and the same final-screen lifecycle.

Preparation checks cover missing generated inputs, unchanged-input cache reuse,
changed-input invalidation, direct argv and working-directory fidelity, dry-run
planning, and failure before measurement. The native terminal suite also generates
an absent candidate script, runs repeated preparation before rotating launches,
cleans the reserved experiment directory during preparation, and verifies retained
logs, machine-readable failures, and result-only stdout.
