<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Running benchmarks

These commands assume a source build and run from the repository root. For an
installed SDK, use the [Java launcher](GETTING_STARTED.md) in place of `build/smh`.

## Fixed measurements

```sh
smh build --config examples/build.json
smh run build/example/benchmarks --config examples/run.json
```

The generated executable also accepts the configuration directly:

```sh
build/example/benchmarks examples/run.json
```

A run configuration selects cases and overrides annotations:

```json
{
  "include": "ArraySum",
  "params": { "size": ["16", "1024"] },
  "warmupIterations": 3,
  "measurementIterations": 5,
  "warmupNanos": 100000000,
  "measurementNanos": 100000000,
  "output": "build/results.json"
}
```

`include` is a Java regular expression. Parameter values are **strings**, even
for numeric fields. Durations are integer nanoseconds. Omitted settings defer to
annotations. `modes` can select `AverageTime`, `Throughput`, or `SingleShotTime`.
Run `output` paths are relative to the **process working directory**.

Each benchmark/parameter/mode combination receives fresh state. Average-time and
throughput iterations repeat work to reach their duration; single-shot iterations
execute one batch. Warmup runs the same lifecycle but contributes no measured
samples or observer events.

## Batch calibration and stabilization

Run the complete checked-in policy:

```sh
smh run build/example/benchmarks --config examples/calibrated-run.json
```

The [configuration](../examples/calibrated-run.json) combines automatic batch
selection with separate bounded warmup and measurement policies. For example,
this minimal standalone run policy stabilizes measurement:

```json
{
  "measurementStability": {
    "minimumSamples": 8,
    "maximumSamples": 24,
    "windowSize": 4,
    "consecutiveWindows": 2,
    "maximumRelativeDeviation": 0.05,
    "maximumRelativeDrift": 0.03,
    "maximumRelativeSlope": 0.02
  }
}
```

All seven fields are required. Use the same shape under `warmupStability` to
stabilize warmup. A policy replaces the fixed iteration count for that phase;
do not also supply its `warmupIterations` or `measurementIterations` override.
The report retains every observation and ends with `STABLE` or `LIMIT_REACHED`.
The limit is a sample count, not a hard timeout.

For automatic batch selection, add:

```json
{
  "batchCalibration": {
    "targetNanos": 100000,
    "maximumBatchSize": 65536,
    "maximumAttempts": 17,
    "samplesPerAttempt": 3
  }
}
```

Calibration runs after trial setup and before warmup. It doubles the batch size
within these bounds and requires every probe in an attempt to reach the target.
Raw probes and exhaustion remain visible. This selects a duration-sized batch;
it does not measure timer resolution or subtract overhead.

To select a fixed batch instead, set `"wallBatchSize": 64`. The default is 1.
Batches greater than one and automatic calibration reject invocation fixtures.
Calibration also rejects simulation and an explicit batch size greater than one.
Changing batch size can change cache and allocation behavior, so retain it when
comparing results.

## Reading results

Wall results follow the JMH JSON structure with an additional `smh` object.

| Field | Interpretation |
| --- | --- |
| `primaryMetric.score`, `scoreUnit` | Aggregate score and unit; throughput is operations per time, other modes are time per operation. |
| `primaryMetric.rawData` | Measured scores retained for independent analysis. |
| `primaryMetric.scoreError`, `scoreConfidence` | `null`: confidence inference is not implemented. |
| `smh.warmup`, `smh.measurement` | Histories, work counts, and stopping policy/status. |
| `smh.batchCalibration` | Selection policy, raw probes, and outcome when enabled. |
| `smh.accuracy` | `unqualified`; a successful run is not an accuracy certification. |
| `smh.sinkQualification` | `unverified` in ordinary reports; compiler fixtures are separate evidence. |
| `smh.jitQuiescence` | `unknown`; statistical stability is not JIT-event stabilization. |

Inspect one result with `jq`:

```sh
jq '.[0] | {benchmark, params, primaryMetric, smh}' build/results.json
```

Keep optimization, PGO, layers, batch size, work budget, and machine identity
with comparisons. Training, sanitizer, and coverage results are validation data.
See [measurement semantics](MEASUREMENT.md) and the [evidence ledger](ASSURANCE.md).

## Java API

SMH uses JMH 1.37 annotations and `org.openjdk.jmh.infra.Blackhole`.
The annotation processor generates direct calls; native discovery needs no
reflection.

| API | Supported behavior |
| --- | --- |
| `@Benchmark` | Public methods; arguments may be states or typed JMH `Blackhole`. Returned values are consumed. Checked exceptions propagate. |
| `@State` | Public concrete classes with public no-argument constructors; `Thread` and `Benchmark` scopes in one worker. Nested classes must be static. |
| `@Param` | Public mutable instance fields: primitives, strings, enums. Lists form a Cartesian product; enum defaults enumerate constants. Names must be unique across a method's states. |
| `@Setup`, `@TearDown` | Public no-argument void methods at Trial, Iteration, or Invocation level, including inherited fixtures. |
| `@BenchmarkMode` | `AverageTime`, `Throughput`, `SingleShotTime`. |
| `@Warmup`, `@Measurement` | Counts and durations; method settings override class settings. JMH annotation batching is unsupported; use run configuration batching. |
| `@OutputTimeUnit`, `@OperationsPerInvocation` | Score units and operation normalization. |
| `@Threads(1)`, `@Fork(1)` | Single-worker/single-process declarations. Other counts and JVM fork arguments are rejected. |

Groups, multiple workers, JMH runner internals, and JIT/Crema execution are outside
the current API. Setup failure rolls back completed states; a failing setup
method must release its own partially acquired resources. Teardown attempts all
completed states in reverse order and retains subsequent failures as suppressed
exceptions. See the [lifecycle contract](MEASUREMENT.md#region-state-machine).

## Animated terminal output

`smh run` and `smh bench` show each completed benchmark case immediately, while
remaining cases continue. A compact status line animates between results. The
spinner indicates activity and elapsed time, not a completion percentage. Binaries
built with an older SDK still work but can only display their final report; rebuild
them to enable completed-case streaming.

Each wall-time result leads with a rounded mean and a histogram of iteration scores.
The histogram includes every finite score in equal-width bins over the full observed
range, with numeric count and score axes. The compact caption shows median, p99,
and iteration count. Percentiles describe **iteration scores**, not individual-operation
latency or confidence intervals. Terminal output uses µs; raw JSON retains the
original values and JMH-compatible `us` units.
Single-shot cases carry an `[ss]` label. Batches larger than one are shown explicitly;
the default batch caption is omitted.

At widths of 110 columns or more, a run-order chart appears beside the histogram.
Each column covers a contiguous group of iterations and displays its minimum and
maximum, preserving spikes even when many observations share a column. Both plots
describe observed variation; neither establishes convergence or accuracy. Layout
uses `COLUMNS` when available, defaults to 80 columns, and supports 40–160 columns.

Cyan emphasizes current measurements, violet distinguishes the run-order plot, and
muted axes keep the data prominent. Comparison bars use cyan for a decrease and
violet for an increase; amber identifies limits. Labels and signed values preserve
the meaning without color.

Unassessed phases are omitted. Non-success stopping reasons remain visible.
There are no warmup sparklines or inferred JIT curves. VM/JDK details appear once
in the final footer. Full observations and phase metadata remain in the JSON report.

Native compilation saves full commands and diagnostics to logs, printing a compact
completion line and a path relative to the working directory. Failures replay diagnostics; `--verbose` also shows
successful build logs. Routine Micronaut startup logs are muted.

Choose `--display auto`, `--display plain`, or `--display tui`.
Automatic animation is disabled for redirected output, `CI`, `TERM=dumb`, and
`NO_COLOR`. Explicit `tui` forces ANSI output. Presentation runs in the parent CLI,
outside benchmark measurement regions, though its CPU activity can still affect
the machine; use plain mode for unattended performance collection.

Interactive runs retain the raw stdout report under `build/smh-reports/` and print
its relative path. A run configuration's `output` file is also written as usual. Redirected
stdout remains the original JSON bytes; animation and summaries use stderr:

```sh
smh run build/example/benchmarks --config examples/run.json --display tui > results.json
```

Child failures preserve their exit code and diagnostics. Cases already rendered
remain available as individual raw JSON files in the reported relative directory. The renderer restores the
cursor on completion, interruption, and process shutdown. See the
[nested Elide application](../examples/invoice-app/README.md) for a complete workflow.

To compare with an earlier SMH wall run, preserve its JSON and pass it as a baseline:

```sh
cp build/results.json baseline.json
smh run build/example/benchmarks --config examples/run.json --compare baseline.json
```

The baseline is read before the child starts, so writing a new result cannot change
the comparison halfway through. Wall-time rows match on benchmark name, mode,
parameters, and score unit. Each completed case adds a compact row with its current
mean, signed percentage, and a bar centered on zero. All rows share a ±20% scale;
an arrow identifies a change beyond that range. Deltas are
`(new / baseline - 1) × 100`; the direction refers to the row's score, so lower
elapsed time and higher throughput have opposite signs. Missing matches and zero
baselines show no percentage.
Comparisons require terminal mode (`--display tui` when redirecting stdout).

These are descriptive score changes, not statistical significance or automatic
regression decisions. Verify compiler/build identity and measurement conditions
before interpreting them. The raw JSON retains every sample and its original order.

`smh simulate` supports the same display modes. Its result leads with instructions
per operation and shows available memory and branch events on a shared scale. With
a compatible saved Callgrind baseline, instructions and up to three other events
get baseline/current pairs; each pair shares a scale starting at zero. The complete
event set remains in the raw report. Cyan identifies current counts and violet
identifies the baseline. Lower simulated event counts do not establish lower
hardware elapsed time.
Without a baseline, the instruction trace follows iteration order; long traces
show the peak of each contiguous group, as labeled. This differs from the wall-time
chart's min/max envelopes and can hide dips within a group.

```sh
smh simulate build/simulation/benchmarks \
  --config examples/simulation-run.json --output build/simulation-run-2 \
  --compare build/simulation-run-1/results.json
```

Simulation comparisons require collected Callgrind results with matching producer,
model, architecture, measurement region, sink, and work configuration. A mismatch
withholds the percentage and explains why; host/build equivalence still needs review.
Plain or redirected output remains JSON, and raw dumps and the full report remain in
the simulation output directory. See [simulation](SIMULATION.md) for collection
requirements and model limits.

## Build once per input revision, measure every time

```sh
smh bench --build-config smh-build.json --config smh-run.json
smh bench --rebuild  # force a fresh native artifact
```

The default filenames are `smh-build.json` and `smh-run.json`. Build entries live in
`<output>/.smh-cache/`; published artifact directories are immutable. SHA-256 keys
include source contents, application/classpath dependencies, SDK jars, compiler
implementations and versions, build options, profile data, platform, and build
environment (excluding terminal, working-directory, and temporary-directory bookkeeping). Artifact checksums
are verified before reuse. Changed inputs during compilation prevent publication;
failed builds never become cache hits. Measurement settings and results are not
cached: every invocation starts a new benchmark process.

Declare external files referenced by native compiler options in the build JSON's
`"cacheInputs": ["path/to/native-library", "path/to/config"]`. Paths resolve relative
to that JSON. This is a local cache, not a hermetic toolchain: undeclared external
native dependencies require `--rebuild`. Source directories must not contain build
output; directory symlinks require explicit resolved input paths. Processes sharing
an output directory serialize cache publication. Existing base-layer sharing across
different output directories is not a cross-process build lock.

The nested sample's `elide run bench` first builds its application with Elide and
packages a deterministic JAR, then invokes `smh bench`. Unchanged application bytes
therefore preserve the native cache key. See its [guide](../examples/invoice-app/README.md).
