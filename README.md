
## `smh`

> **S**ubstrateVM **M**icrobenchmark **H**arness

`smh` enables (relatively) painless benchmarking as a (roughly) drop-in replacement for [JMH](https://github.com/openjdk/jmh) when Native Image is your intended target.

### Why?

JMH is the right way to benchmark Java which targets JVMs. It is really good at making sure you get strong trustable numbers in the presence of Hotspot JIT behavior (e.g. compiler blackholing).

That's great, but when you're targeting [SubstrateVM](https://github.com/oracle/graal/blob/master/docs/reference-manual/native-image/README.md) (aka Native Image), you are effectively executing in a completely different environment from Hotspot. Although JIT behavior may still be active (e.g. through [Crema](https://github.com/oracle/graal/issues/11327) with Graal JIT turned on host-side), what you're really hoping for is efficient machine codegen.

`smh` plugs this gap by providing a JMH-style benchmark harness, but specifically targeting Substrate.

### How?

Substrate can execute programs built AOT, in part or in whole; with Truffle involved, Substrate can execute managed languages like JavaScript (GraalJs), Python (GraalPython), and WASM (GraalWasm).

SMH provides:

- **JMH-compatible API** with which to framework out your benchmark cases and matrices. This API should feel familiar, if extended, for anyone whom has used JMH before.

- **Bootstrappers with hooks** which define regions for benchmarking; these are compatible with e.g. Codspeed, Valgrind, and other native benchmarking technologies. By "bootstrapper," we mean: a native Rust entrypoint which stands up Substrate/Graal for you and executes your benchmarks, and a native Crema-loading entrypoint, and a native AOT builder. By "hooks," we mean a general API which can denote areas of interest to benchmark observers at runtime.

- **Native Image base layer** which encompasses the necessary host-side code to execute benchmarks considering the above, so that, to build and run _your_ benchmarks, most of the Native Image AOT work is already done for you.

### How do I use it?

SMH is itself distributed as a Native Image CLI, which can then dispatch based on CLI flags and/or a JSON configuration. How that JSON configuration is produced is up to you: you can make it from Gradle, or Maven, or Elide.

SMH is designed to produce result formats identical to JMH and as expected by other tools, like Bencher, Codspeed, Criterion, and others.

### Working AOT implementation

The initial implementation is a **Micronaut/Picocli CLI built with Elide**, in the
`dev.elide.smh` namespace. It uses JMH 1.37's actual annotations. An annotation
processor generates direct Java calls, parameter assignments, fixture calls, and
a closed-world benchmark registry before Native Image runs. Benchmark discovery
and invocation do not require reflection in the native executable.

This phase implements single-thread AOT execution. Compiler blackholing,
dead-code-elimination protection, statistical confidence guarantees, JMH runner
API compatibility, multi-thread execution, Crema/JIT, guest-language benchmarks,
and dedicated Bencher/Codspeed/Criterion exporters remain future work. The current
output follows the JMH JSON structure; it does not claim JMH's measurement
guarantees. Return values are currently discarded, so the example demonstrates
the workflow rather than providing trustworthy performance comparisons yet.

### Setup

Install [mise](https://mise.jdx.dev), then:

```sh
mise trust
mise install
mise run build
mise run test
```

`mise.toml` pins Elide's November 3, 2026 nightly and Rust. Elide resolves Maven
dependencies and performs Java compilation, including Micronaut's bean definition
processor and Picocli's Native Image metadata processor. There is no Gradle or
Maven build to maintain. Python 3 orchestrates packaging and integration checks.

Native builds additionally need a **GraalVM JDK 25** with `native-image` on `PATH`,
and its supported host C compiler/linker. PGO requires a GraalVM edition supporting
PGO. `java` is used for the development CLI and test driver; benchmark execution
uses native binaries. This implementation was exercised on Linux x86-64 with
Oracle GraalVM 25.3.4.1 / JDK 25.0.4.1. Other platforms have not been validated.
Run the commands below in a mise-activated shell, or prefix them with `mise exec --`.

```sh
mise run native
build/smh --help
```

The distribution in `build/` contains `smh`, `smh-bootstrap`, `smh.jar`,
`smh-runtime.jar`, and `lib/`. Keep the JARs and `lib/` together: the native CLI
uses them to compile new benchmark suites. Pass `--home /path/to/distribution`
when the build configuration does not specify their location.

While developing the CLI, use this command in place of `build/smh`:

```sh
mise exec -- java -cp 'build/smh.jar:build/lib/*' dev.elide.smh.cli.Main --help
```

`mise exec -- elide build` compiles the project directly, and
`mise exec -- elide test` runs the functional integration checks through JUnit.
`mise run build` also assembles the distribution JARs.

### Build and run benchmarks

See [ArraySum.java](examples/src/dev/elide/smh/examples/ArraySum.java),
[build.json](examples/build.json), and [run.json](examples/run.json).

```sh
build/smh build --config examples/build.json
build/smh run build/example/benchmarks --config examples/run.json
# The executable also runs directly:
build/example/benchmarks examples/run.json
```

Build configuration paths are relative to the build JSON file. `sources` accepts
Java files or directories, which are searched recursively. `classpath` accepts
additional JARs or class directories. The optional `nativeImage` field chooses
the compiler executable. `nativeArgs` forwards compiler options, including GC,
CPU target, memory limits, and build parallelism.

Run configuration supports `include` (a Java regular expression), `params`
(parameter names mapped to arrays of string values), `modes`, `warmupIterations`,
`measurementIterations`, `warmupNanos`, `measurementNanos`, and `output`.
Omitted settings defer to annotations. Run `output` is relative to the process's
working directory. Results are also printed as JSON to stdout; compiler output
and diagnostic messages are separate from benchmark execution.

The supported annotation surface is:

| Annotation | Behavior |
| --- | --- |
| `@Benchmark` | Public methods with no arguments or `@State` arguments; checked exceptions propagate. |
| `@State` | Public concrete classes with public no-argument constructors; `Thread` and `Benchmark` scopes in a single worker. Nested classes must be static. |
| `@Param` | Public mutable instance fields: primitives, strings, and enums. Explicit value lists form a Cartesian product; enum defaults enumerate constants. Names must be unique across a method's states. |
| `@Setup`, `@TearDown` | Public, no-argument void methods at Trial, Iteration, and Invocation levels, including inherited fixtures. Teardown unwinds in reverse order. |
| `@BenchmarkMode` | `Throughput`, `AverageTime`, and `SingleShotTime`. |
| `@Warmup`, `@Measurement` | Iteration counts and durations, with method settings overriding class settings. Batching is deferred. |
| `@OutputTimeUnit`, `@OperationsPerInvocation` | Score units and operation normalization. |
| `@Threads(1)`, `@Fork(1)` | Accepted single-worker/single-process declarations. JVM arguments and other fork settings are rejected. |

Every benchmark/parameter/mode combination gets fresh state and a Trial lifecycle.
Warmup and measurement both execute fixtures. Only measurement contributes samples
and observer events. Single-shot mode executes one invocation per iteration.
Scores use elapsed invocation time; the initial runner times each invocation
individually, including timer overhead, and excludes invocation fixtures. This
is a functional baseline for subsequent measurement-integrity work.

Unsupported modes, group scopes, blackhole/control arguments, inaccessible state,
and unsupported JMH options fail compilation. The API is a supported subset of
JMH's annotation API, rather than a replacement implementation of JMH internals.
Results include per-iteration samples under `primaryMetric.rawData`; unavailable
confidence statistics are represented as null values in JSON.

### Optimization and layers

```sh
build/smh build --config examples/build.json -O1 --output build/O1
build/smh build --config examples/build.json -O2 --output build/O2
build/smh build --config examples/build.json -O3 --output build/O3
```

SMH puts stable runtime code in a base layer and leaves benchmark classes,
generated adapters, and custom observers in the application image. Base layers
are cached under `build/layers/<key>/smh-base.nil`. The key covers the reported
compiler version, platform, runtime dependency contents, optimization, all common
native options, and PGO profile contents. Options are applied to **both** the base
layer and application build. An O1 application therefore consumes an O1 base,
and an O3 application consumes an O3 base.

An output `build.json` records the settings and selected layer key. Only the base
is cached; the application image is rebuilt. Layer library names include the
variant key. The builder extracts the native library next to the executable and
sets a relative loader path; deploy that library alongside the executable.
The `.nil` archive is needed for building consumers, not for running them.
Use `--no-layers` or `"layers": false` to build a complete image.

[Native Image Layers](https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.core/src/com/oracle/svm/core/imagelayer/NativeImageLayers.md)
are experimental and compiler-specific. SMH currently uses their hosted
`-H:LayerCreate` / `-H:LayerUse` interface. Layer compatibility remains enforced by
Native Image; failures are surfaced without substituting a differently built layer.

### PGO

The tested GraalVM rejects **layered PGO instrumentation** with a
`DISALLOW_LAYERED` diagnostic. Use the complete-image path for its PGO workflow:

```sh
build/smh build --config examples/build.json --no-layers \
  --pgo instrument --output build/instrument
build/smh run build/instrument/benchmarks --config examples/run.json \
  --profile-output build/training.iprof
build/smh build --config examples/build.json --no-layers -O3 \
  --pgo use --profile build/training.iprof --output build/pgo
build/smh run build/pgo/benchmarks --config examples/run.json
```

Normal process exit flushes the training profile. Do not use instrumented timings
as final measurements. The compiler's output identifies `PGO: instrument` and
then `PGO: user-provided` for these stages. See GraalVM's
[PGO documentation](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/PGO/).

For toolchains supporting layered PGO, the same build API forwards instrumentation
or profile-use options to both layer builds and separates their cache variants.
SMH does not silently disable PGO or fall back from a failed layered build. The
verified PGO workflow currently uses executables; shared-library profile dumping
is deferred.

### Rust bootstrap and observer hooks

```sh
build/smh build --config examples/build.json --shared --output build/shared
build/smh run build/shared/libbenchmarks.so --config examples/run.json \
  --bootstrap build/smh-bootstrap
```

The Rust bootstrapper loads the library, calls `graal_create_isolate`, invokes
`smh_run`, and calls `graal_tear_down_isolate`, reporting failures at each stage.
The library and its dependent base-layer library must remain together.
The example filename above is for Linux; other platforms use their normal shared
library extension.

Implement `dev.elide.smh.runtime.Observer` to integrate benchmark observers.
Set `"observer": "your.package.ObserverClass"` in the build JSON and include its
source or classpath entry. The processor generates its constructor call directly.
`beforeIteration` and `afterIteration` bracket measured iterations and receive the
benchmark name, parameter values, and iteration index. Iteration fixtures and
warmup are outside the region; invocation fixtures remain inside the region.
`afterIteration` also runs when benchmark execution fails. Observers can connect
these regions to native instrumentation APIs. Tool-specific Codspeed/Valgrind
adapters are not bundled yet.

### Validation

`mise run test` checks generated adapters, primitive and enum parameter matrices,
all fixture levels, observer boundaries, failure cleanup, JSON output, run
overrides, unsupported features, and distinct optimization/PGO layer keys. Native
builds are deliberately separate from these fast checks. End-to-end validation
on the toolchain above covered the native Micronaut CLI, O1/O2/O3 layered
executables, the Rust/shared-library path, and a complete PGO training/rebuild/run
cycle. These checks verify functionality; they do not establish measurement
integrity.

To repeat the native checks with the installed compiler, use `mise run test-native`.
Use `mise run test-pgo` to include full-image PGO training and profile consumption.
These build the native distribution first and write compiler logs under
`build/native-tests/`. They also verify that an executable copied with its base
library runs from a different directory.

