<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Running benchmarks

These commands assume a source build and run from the repository root. For an
installed SDK, use the [Java launcher](GETTING_STARTED.md) in place of `build/smh`.

## Fixed measurements

```sh
build/smh build --config examples/build.json
build/smh run build/example/benchmarks --config examples/run.json
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
build/smh run build/example/benchmarks --config examples/calibrated-run.json
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
