<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# AOT builds

SMH compiles Java sources and generates direct-call adapters before invoking
Native Image. These examples assume the source distribution in `build/`.

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

Preview the compiler commands:

```sh
build/smh build --config examples/build.json --dry-run
```

## Source compiler selection

By default, SMH uses `elide javac` when Elide is on `PATH`. Otherwise it selects
`JAVA_HOME/bin/javac`, then the running JVM's Java home. An explicit `--java-home`
(or JSON `javaHome`) takes precedence over automatic selection:

```sh
build/smh build --config examples/build.json --java-home /path/to/graalvm
```

An invalid selected JDK fails with its compiler path in the diagnostic. Compiler
errors propagate; SMH does not retry failed compilation with a different tool.
The `nativeImage` setting independently selects the native compiler.

## Optimization and layers

Build separate output directories for each optimization level:

```sh
build/smh build --config examples/build.json -O1 --output build/O1
build/smh build --config examples/build.json -O2 --output build/O2
build/smh build --config examples/build.json -O3 --output build/O3

build/smh run build/O1/benchmarks --config examples/run.json > build/O1/results.json
build/smh run build/O2/benchmarks --config examples/run.json > build/O2/results.json
build/smh run build/O3/benchmarks --config examples/run.json > build/O3/results.json
```

Each command retains its JSON stdout beside the corresponding binary. The
example configuration also updates `build/results.json`; use the per-variant
files above when comparing builds.

Layer builds apply optimization, native arguments, and PGO settings to both the
runtime base and benchmark image. Cache identity includes compiler version,
target, dependencies, options, and profile contents. Keep generated layer
libraries beside the executable. Use `--no-layers` for a monolithic image or a
compiler without compatible layer support.

Do not rebuild the SDK JARs while a native build is using them. Cache publication
and immutable input snapshots for concurrent builds remain open work.

## Profile-guided optimization

Use a compiler distribution that supports PGO. The exercised workflow is
monolithic: instrument, run representative training, then rebuild using the
profile.

```sh
build/smh build --config examples/build.json \
  --no-layers --pgo instrument --output build/instrument

build/smh run build/instrument/benchmarks --config examples/run.json \
  --profile-output build/training.iprof

build/smh build --config examples/build.json \
  --no-layers -O3 --pgo use --profile build/training.iprof --output build/pgo

build/smh run build/pgo/benchmarks --config examples/run.json
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
build/smh build --config examples/build.json --shared --output build/shared
build/smh run build/shared/libbenchmarks.so --config examples/run.json \
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
