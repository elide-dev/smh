<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

## `smh`

> **S**ubstrateVM **M**icrobenchmark **H**arness

Repository: [elide-dev/smh](https://github.com/elide-dev/smh). Licensed under
[Apache-2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT), at your option.

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

The [feature roadmap](ROADMAP.md) tracks wall time and instruction/cache
simulation, stabilization, compiler qualification, PGO, six platform targets,
coverage, fuzzing/sanitizers, and OpenSSF evidence. The
[measurement contract](docs/MEASUREMENT.md) defines the required guarantees;
the [assurance ledger](docs/ASSURANCE.md) records which evidence actually exists.

This phase implements single-thread AOT execution, typed compiler sinks, and
bounded statistical warmup and measurement convergence. Complete compiler/CPU
qualification, statistical confidence guarantees, JMH runner
API compatibility, multi-thread execution, Crema/JIT, guest-language benchmarks,
and dedicated Bencher/Codspeed/Criterion exporters remain future work. JIT
compilation/deoptimization monitoring and Codspeed integration are also pending.
Callgrind instruction/cache simulation works on the local Linux amd64 baseline,
with fixed work budgets and retained raw dumps. Statistical convergence alone
does not qualify accuracy. Wall output follows the JMH JSON structure;
simulation has a distinct event-count report. Neither claims JMH's measurement
guarantees. Returned values and explicit JMH `Blackhole` parameters use JMH's
typed `consume` methods. Local O1/O2/O3 and PGO fixtures verify the intrinsic in
final compiler LIR, including a disabled-intrinsic control. This establishes a
concrete compiler result; general optimization resistance, CPU borders, and
trustworthy performance comparisons remain qualification work.

### Releases

GitHub Releases provide `smh-VERSION-sdk.zip` (Java 25 CLI, runtime/API JAR,
and runtime dependencies), `smh-VERSION-linux-amd64.zip` (native CLI and Rust
bootstrap), and `SHA256SUMS`. After extracting the SDK, run
`java -cp 'smh.jar:lib/*' dev.elide.smh.cli.Main --help`; use `;` instead of `:`
on Windows. Native benchmark compilation still requires a compatible GraalVM.
The initial release is experimental; consult the assurance ledger before making
performance claims. Other native distribution targets remain on the roadmap.

Release Please opens version/changelog PRs from conventional commits. Its PRs
receive explicitly dispatched CI checks, because the default GitHub token does
not trigger PR workflows. Maintainers merge a release PR after its checks pass;
Release Please then creates the tag/release and builds and uploads its assets.
Failed asset jobs can be rerun without changing the release tag.

Automatic wall batch calibration is available in `examples/calibrated-run.json`.
It retains every probe and selects a bounded batch before warmup. Calibration
and statistical stabilization have independent limits; reaching a limit remains
visible in results. Neither establishes clock accuracy or JIT quiescence.

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
the compiler executable, or an argument array for a compiler launcher.
Windows defaults to `cmd.exe /d /c native-image.cmd`. `nativeArgs` forwards compiler options, including GC,
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

### Stabilization and measurement evidence

Run JSON accepts optional `warmupStability` and `measurementStability` policies.
Each replaces the annotation's fixed iteration count for that phase; specifying
both a policy and an explicit fixed count for the same phase is an error.
All seven fields are required, for example:

```json
{
  "warmupStability": {
    "minimumSamples": 10,
    "maximumSamples": 100,
    "windowSize": 5,
    "consecutiveWindows": 3,
    "maximumRelativeDeviation": 0.02,
    "maximumRelativeDrift": 0.01,
    "maximumRelativeSlope": 0.01
  }
}
```

The monitor checks current-window sample standard deviation divided by mean,
drift between two adjacent window means relative to their larger mean, and
absolute linear trend over the current window relative to its mean. Tolerances
are descriptive checks, without confidence or independence guarantees. Window
size is at least two; minimum samples must cover two windows. The maximum must
allow the declared number of consecutive passing windows.

The `smh` JSON extension retains every warmup/measurement score, elapsed
nanoseconds, operation count, sample order, stopping policy, and stopping reason.
Adaptive phases report `STABLE` or `LIMIT_REACHED`; fixed phases are
`not-assessed`. Results explicitly record `accuracy: unqualified`, the host
execution mode, and JIT quiescence as `unknown`. An AOT host can embed guest/JIT
runtimes, so image detection alone cannot establish quiescence. No samples
are discarded. Limits currently bound sample count; independent phase time/work
limits and JIT event providers remain roadmap items.

Invocation fixtures are excluded from elapsed scores but included in the current
iteration observer region. This difference is recorded in each report. Clock
reversal, zero measured invocation duration, and elapsed/operation overflow fail
the run. Successfully entered runner scopes unwind on failure, retaining the
primary exception and suppressing cleanup failures. Generated multi-state
fixture rollback still needs separate qualification.

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

The Rust bootstrapper uses bindings generated by pinned
[Myna](https://github.com/elide-dev/myna) from [abi/smh.seam](abi/smh.seam).
It resolves every required symbol, creates an isolate, checks all 256 bits of the
generated ABI fingerprint, invokes `smh_run`, and tears down the isolate once.
Entry and teardown failures retain the primary exit status and both diagnostics.
The library and its dependent base-layer library must remain together.
The example filename above is for Linux; other platforms use their normal shared
library extension. See [the FFI contract](docs/FFI.md) for ownership, generation,
and the limited Windows adaptation.

Implement `dev.elide.smh.runtime.Observer` to integrate benchmark observers.
Set `"observer": "your.package.ObserverClass"` in the build JSON and include its
source or classpath entry. The processor generates its constructor call directly.
`beforeIteration` and `afterIteration` bracket measured iterations and receive the
benchmark name, parameter values, and iteration index. Iteration fixtures and
warmup are outside the region; invocation fixtures remain inside the region.
`afterIteration` also runs when benchmark execution fails. Observers can connect
these regions to native instrumentation APIs. Tool-specific Codspeed/Valgrind
client-request adapters are not bundled yet. The Callgrind backend below uses
its supported function-boundary collection controls.

### Instruction/cache simulation

On a Linux worker with Valgrind installed:

```sh
build/smh build --config examples/simulation-build.json
build/smh simulate build/simulation/benchmarks \
  --config examples/simulation-run.json --output build/simulation-run-1
```

The run config selects `"backend": "callgrind"` and a positive
`invocationsPerIteration` budget. Each measured batch executes exactly that many
invocations without clock reads. Trial/iteration fixtures, warmup, and reporting
are outside collection; invocation-fixture benchmarks are rejected. Both
monolithic and layered O2 images have passed the local native checks.

Results retain exact instruction/cache/branch counts, the declared cache model,
raw dump hashes, and instruction-per-operation ratios. Missing, contradictory,
leaking, or extra region data fails the import. Accuracy remains unqualified.
Online simulation convergence, Codspeed, and remote-worker orchestration remain
open. See [the simulation contract and evidence](docs/SIMULATION.md).

### Validation

```sh
mise run lint
mise run test-jvm
mise run coverage-kernel
mise run coverage
mise run coverage-rust
mise run coverage-rust-branch
mise run check-abi
mise run test-tools
mise run test-portable
```

Elide produces JUnit XML and JaCoCo XML/HTML under `.dev/reports/`. The coverage
checker counts owned production Java separately from test/generated code and
rejects reports missing a production source. Its final gate requires 99% lines
and branches; the statistics kernel requires 100% of both. The current local
Java run passes: **99.51% lines, 99.43% branches**, including the annotation
processor and builder. The statistics kernel has 100% of both. Rust's stable
profile covers **99.31% of lines**, including `build.rs` (runtime sources: 100%).
A separate pinned nightly profile covers **30/30 instrumented branch outcomes**.
These compiler-defined counters do not prove every error path or optimizer
behavior. Profiles use separate build directories; absent branch instrumentation
cannot pass the branch gate.

With Native Image and `objdump` installed, `mise run qualify-compiler` checks
twelve actual generated sink adapters at O1/O2/O3 and the scheduled clock/call
fixture. `mise run qualify-pgo` adds instrument/train/use. Both retain graphs,
final LIR, disassembly, executable hashes, commands, and a deliberately broken
intrinsic control under `build/qualification/`. Reports continue to label accuracy
as unqualified and sink qualification as unverified: these fixtures do not prove control-flow
dominance, CPU serialization, or every application/layer configuration.

With Clang installed, `mise run check-abi-lowering` cross-compiles the C ABI for
all six targets and compares it with Myna's LLVM declarations. This checks ABI
lowering; it does not qualify native execution on those targets or apply ThinLTO
optimizer contracts.

`mise run test-simulation` exercises real Callgrind against monolithic and layered
images, checks repeatability and fixed-work scaling, perturbs warmup/fixture work,
and requires the production importer to reject disabled and unbounded collection.

Hardened, SHA-pinned workflows configure Codecov coverage/JUnit uploads,
six-platform portable JVM tests, separate Rust coverage profiles, compiler
fixtures, CodeQL, dependency review, Scorecard, and toolchain controls. They have been syntax-checked locally;
hosted execution and repository service configuration remain unverified. Portable
JVM tests and sanitizer installation controls do not qualify native measurements
or establish sanitizer coverage of SMH/SVM. No OpenSSF rating is claimed.

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

Wall run configurations can set `"wallBatchSize": 64` to time groups of 64
invocations with one clock pair. The result records the batch size and normalizes
by all operations. Batching applies to warmup and measurement and rejects
invocation fixtures; the default remains one invocation. See
[measurement policy](docs/MEASUREMENT.md) for calibration and qualification limits.

Run `mise run test-rust-asan` for the instrumented Rust bootstrap/provider suite
and required FFI heap-overflow, use-after-free, and leak negative controls. See
[sanitizer scope and evidence](docs/SANITIZERS.md); SVM and JIT instrumentation
remain separate work.

Use `mise run test-miri` for strict-provenance Rust lifecycle interpretation
under both borrow models, and `mise run fuzz-callgrind` for coverage-guided parser
fuzzing with retained corpora and a required finding/replay control. See
[sanitizer scope](docs/SANITIZERS.md) and [fuzzing](docs/FUZZING.md).


`mise run fuzz-run-config` fuzzes run JSON and exact-value round trips. Run fields
enforce their declared JSON types: quoted counts and numeric parameter values
are rejected instead of coerced. Programmatic callers can use
`RunConfig.fromJson(JsonElement)` without filesystem access.
