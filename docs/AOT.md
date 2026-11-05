<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# AOT builds

SMH compiles Java sources and generates direct-call adapters before invoking
Native Image. These examples assume the source distribution in `build/`, with
that directory on `PATH` so the command is `smh`.

Use `smh bench --build-config examples/build.json --config examples/run.json` to
reuse verified, unchanged build artifacts and run fresh measurements in one command.
See [cache behavior and limits](USAGE.md#build-once-per-input-revision-measure-every-time).

## Build configuration

```json
{
  "home": "../build",
  "sources": ["src"],
  "output": "../build/example",
  "optimization": "2",
  "pgo": "off",
  "layers": true,
  "nativeArgs": ["--parallelism=4", "-J-Xmx4g"]
}
```

This is [examples/build.json](../examples/build.json). **Paths in build JSON are
relative to that JSON file.** CLI `--home`, `--profile`, and `--output` paths are
relative to the process working directory. The same distinction applies to
JSON `javaHome` and CLI `--java-home`.

| Setting | Purpose |
| --- | --- |
| `home` | Distribution containing `smh.jar`, `smh-runtime.jar`, and `lib/`. |
| `sources` | Java files or recursively searched directories. |
| `classpath` | Additional dependency JARs or class directories. |
| `output` | Directory for generated code, metadata, and native output. |
| `javaHome` | Explicit JDK for source compilation; selects `bin/javac` (`javac.exe` on Windows). |
| `nativeImage` | Compiler executable or an array of launcher arguments. Windows defaults to `cmd.exe /d /c native-image.cmd`. |
| `nativeArgs` | Compiler options for memory, parallelism, CPU target, GC, and diagnostics. |
| `observer` | Observer implementation class compiled into the benchmark. |
| `cacheInputs` | Extra files/directories referenced by native options, hashed by `smh bench`. |

Preview the compiler commands:

```sh
smh build --config examples/build.json --dry-run
```

## Source compiler selection

By default, SMH uses `elide javac` when Elide is on `PATH`. Otherwise it selects
`JAVA_HOME/bin/javac`, then the running JVM's Java home. An explicit `--java-home`
(or JSON `javaHome`) takes precedence over automatic selection:

```sh
smh build --config examples/build.json --java-home /path/to/graalvm
```

An invalid selected JDK fails with its compiler path in the diagnostic. Compiler
errors propagate; SMH does not retry failed compilation with a different tool.
The `nativeImage` setting independently selects the native compiler.

## Optimization and layers

Build separate output directories for each optimization level:

```sh
smh build --config examples/build.json -O1 --output build/O1
smh build --config examples/build.json -O2 --output build/O2
smh build --config examples/build.json -O3 --output build/O3

smh run build/O1/benchmarks --config examples/run.json > build/O1/results.json
smh run build/O2/benchmarks --config examples/run.json > build/O2/results.json
smh run build/O3/benchmarks --config examples/run.json > build/O3/results.json
```

Each command retains its JSON stdout beside the corresponding binary. The
example configuration also updates `build/results.json`; use the per-variant
files above when comparing builds.

Layer builds apply optimization, native arguments, and PGO settings to both the
runtime base and benchmark image. Cache identity includes compiler version,
target, dependencies, options, and profile contents. Keep generated layer
libraries beside the executable. Use `--no-layers` for a monolithic image or a
compiler without compatible layer support.

Do not rebuild the SDK JARs while a native build is using them. Base-layer
producers use a shared file lock and publish checksummed archives atomically.
Executable and bytecode caches use immutable generations and check input
contents again before publishing. These checks detect changes; they do not
provide an immutable snapshot of external inputs during compilation.

### Prepare once, reuse across builds

```sh
smh build --config examples/build.json --base-only
smh build --config examples/build.json --bytecode-only
```

The first command prepares just the runtime layer. The second compiles sources
and generated adapters without invoking Native Image. Bytecode is cached under
`HOME/bytecode`, shared across AOT optimization variants and runtime-loaded
execution. Native executables remain separately cached by `smh bench`.

Release packaging includes a separate Linux amd64 layers archive. Extract it
into the SDK directory to install `layers/<identity>/smh-base.nil` and its
checksum. Reuse requires matching compiler contents/version, target, runtime
dependencies, flags, and profile. Installation paths are not part of the layer
identity. A different build variant creates its own layer; SMH never substitutes
an incompatible prebuilt layer.

### Crema/Ristretto and HotSpot/C2

Execution is independent of the measurement backend. These commands run SMH's
wall-clock harness with the same generated workload adapters:

```sh
smh bench --build-config smh-build.json --config smh-run.json --execution aot
smh bench --build-config smh-build.json --config smh-run.json --execution crema
smh bench --build-config smh-build.json --config smh-run.json \
  --execution hotspot-c2 --runtime-java-home /path/to/jdk25
```

`javaHome` selects javac; `runtimeJavaHome` selects the JVM used for execution.
Keep javac fixed when comparing execution modes. The C2 preset disables JVMCI
compilation and tiered compilation explicitly. Unsupported VM flags fail the
run. This requests C2; compilation evidence and quiescence remain separate.

Crema uses the reusable `crema/smh-crema` native worker inside the SDK. Build it once
with `mise run native-crema`, or install the matching Crema release archive.
`SMH_NATIVE_IMAGE` selects its native-image executable. The host is built with
`-H:+RuntimeClassLoading` and `-H:+GraalJITCompileAtRuntime`, preserving
`java.base`, SMH's runtime API, and JMH's blackhole API for runtime-loaded callers.
Additional JDK modules are not currently qualified. `cremaHost` or
`--crema-host` selects a host with an adjacent `.json` build manifest; its binary
and runtime dependency digests must match. Keep its companion JDK libraries in
the same directory; the manifest verifies every native file in the bundle.

Both runtime-loaded modes launch a fresh process. Only benchmark bytecode,
dependencies, and generated adapters enter the payload classloader. The host
owns timers, calibration, lifecycle orchestration, statistics, and reporting.
Reflection resolves the suite once before measurements; invocations use the
typed adapter interface. Crema retains that interface boundary between AOT host
code and runtime-loaded code, which is part of the measured execution model.

`runtimeArgs` accepts system properties, heap options, and diagnostic `-XX:`
options, with compiler-selection overrides rejected. Raw reports retain the
execution backend, requested compiler, complete launch command, payload key,
host identity, and runtime flags. JIT compilation and quiescence are not inferred
from stable timings. Trace-enabled runtime runs are labeled diagnostic, and
`smh bench` labels native PGO instrumentation as training. Neither may be used
for performance deltas. Shared-library execution, custom observers, PGO, and
Callgrind are currently unsupported for runtime-loaded modes.

`mise run test-execution` records cold, unchanged, and changed-source bytecode
preparation times, then exercises both hosts with compilation diagnostics. Its
timings identify their scope and are local observations, not portable latency
guarantees.

For fresh-process startup or sustained soak, use
[`smh bench --mode`](EXPERIMENTS.md). AOT, HotSpot, C2, and Crema hosts support
wall experiments; Linux AOT also supports Callgrind. Delegated JMH and its
Leyden cache workflow currently remain steady-only.

## Delegated JMH and Leyden

The SDK includes `smh-jmh.jar`, JMH core and annotation processor **1.37**, and
pinned runtime dependencies. `smh jmh` compiles sources with JMH's official
processor into a cached `benchmarks.jar`; SMH adapters use a separate cache.
No native compiler is required. JMH owns the generated loops, blackholes, fork
lifecycle, timing and JSON serialization. SMH handles orchestration and display.

```sh
smh jmh --build-config smh-build.json --config smh-run.json \
  --runtime-java-home /path/to/jdk-25 --output results/jmh-hotspot
smh jmh --build-config smh-build.json --config smh-run.json \
  --runtime-java-home /path/to/jdk-25 --aot-cache --output results/jmh-leyden
smh bench --execution crema --build-config smh-build.json --config smh-run.json \
  --compare results/jmh-hotspot/results.json --display tui
```

The default `--execution hotspot` uses tiered C1/C2, with JVMCI disabled.
`--execution hotspot-c2` is available for a C2-only experiment; it cannot be
combined with `--aot-cache`. `--forks` defaults to three fresh forks and must be
positive. One worker is selected explicitly. Fixed iteration counts, durations,
modes and parameters come from the SMH run configuration; omitted settings defer
to JMH annotations/defaults. SMH calibration, batching, simulation and adaptive
stability policies are rejected, since their semantics cannot be translated.
The display adapter supports throughput, average-time, and single-shot modes.
It rejects other JMH result modes and preserves the original files.

Every job requires a **new** `--output` directory. It contains the job description,
execution identity, commands, logs, untouched `jmh-results.json`, and adapted
`results.json`. `--compare` accepts adapted JMH or SMH wall results with matching
benchmark/mode/parameters/units; it reports descriptive deltas, not significance
or cross-harness equivalence. Units are never silently converted. The footer
identifies JMH and Leyden, and plots identify multi-fork iteration order.

`--aot-cache` uses JDK 25's `-XX:AOTCacheOutput` to train in a separate, nonforked
JMH process running the selected workload and configuration. Training is not a
performance result: JMH fork-only compiler hints and blackhole configuration can
differ from this training process. Measurement uses fresh JMH forks with
`-XX:AOTMode=on` and `-XX:AOTCache`. Startup AOT logging verifies that each fork
opened the expected cache and retained the full module graph, without AOT errors;
a failed check retains raw data but does not publish adapted performance results.
JVM log wording changes may require an adapter update; they fail closed.

Caches live under the SDK's writable `jmh-aot` directory. Keys cover source and
classpath content, JMH/SMH artifacts, compiler identity, runtime flags, training
settings, and environment. Completed cache generations are locked, hashed, and
atomically published; missing or corrupt artifacts retrain. Cache paths are local
to this SDK and JDK installation, not portable distribution assets. AOT requires
JAR classpaths. Injected JVM environment options are rejected; use `runtimeArgs`.
JMH fork options from `@Fork` are overridden by this explicit job configuration.

Leyden improves startup/warmup through cached classes, linking and method
profiles; this is not Native Image or a cache of compiled C2 methods. JMH
steady-state scores do not establish a startup advantage. See
[the JDK AOT API](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/jdk/management/HotSpotAOTCacheMXBean.html),
[JEP 514](https://openjdk.org/jeps/514), and [JEP 515](https://openjdk.org/jeps/515).
`mise run test-jmh` exercises actual JMH forks, Leyden creation/reuse/source
invalidation, raw-data preservation, visuals and failure propagation.

## Profile-guided optimization

Use a compiler distribution that supports PGO. The exercised workflow is
monolithic: instrument, run representative training, then rebuild using the
profile.

```sh
smh build --config examples/build.json \
  --no-layers --pgo instrument --output build/instrument

smh run build/instrument/benchmarks --config examples/run.json \
  --profile-output build/training.iprof

smh build --config examples/build.json \
  --no-layers -O3 --pgo use --profile build/training.iprof --output build/pgo

smh run build/pgo/benchmarks --config examples/run.json
```

Training timings are not performance results. Keep the profile, build metadata,
compiler identity, and final executable together for comparisons.

Layered PGO depends on the compiler: the locally exercised Oracle compiler
rejects layered instrumentation with `DISALLOW_LAYERED`. SMH forwards the options
and reports that failure; it does not silently change the build. Shared-library
PGO profile dumping is not implemented.

## Shared libraries

On Linux amd64, build a shared image and run it through the Rust bootstrap:

```sh
smh build --config examples/build.json --shared --output build/shared
smh run build/shared/libbenchmarks.so --config examples/run.json \
  --bootstrap build/smh-bootstrap
```

The bootstrap resolves the ABI, checks Myna's full fingerprint, creates an
isolate, calls the benchmark, and tears down the isolate. Platform library names
vary; native shared-image evidence currently covers Linux amd64. See the
[FFI contract](FFI.md) before extending this boundary.

## Observers

Implement `dev.elide.smh.runtime.Observer` and select its class with the build
configuration's `observer` field. Observers bracket measured iterations; warmup,
trial/iteration fixtures, and reporting are outside. Invocation fixtures are
inside the observer region even though their duration is excluded from the wall
score. Callgrind uses its own [collection boundary](SIMULATION.md#work-and-boundaries).
