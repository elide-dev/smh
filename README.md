<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

![SMH — SubstrateVM Microbenchmark Harness](docs/assets/smh-repo-banner.png)

[![CI](https://github.com/elide-dev/smh/actions/workflows/quality.yml/badge.svg?branch=main)](https://github.com/elide-dev/smh/actions/workflows/quality.yml)
[![Release](https://img.shields.io/github/v/release/elide-dev/smh)](https://github.com/elide-dev/smh/releases/latest)
[![Codecov](https://codecov.io/gh/elide-dev/smh/branch/main/graph/badge.svg)](https://codecov.io/gh/elide-dev/smh)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/elide-dev/smh/badge)](https://scorecard.dev/viewer/?uri=github.com/elide-dev/smh)
[![License: Apache-2.0 OR MIT](https://img.shields.io/badge/license-Apache--2.0%20OR%20MIT-blue)](LICENSE)
[![Discord](https://img.shields.io/discord/1119121740161884252?logo=discord&logoColor=white&label=Discord)](https://elide.dev/discord)
[![Built with Elide](https://img.shields.io/badge/built%20with-Elide-8A2BE2)](https://elide.help)
![Java 25+](https://img.shields.io/badge/-Java%2025%2B-blue.svg?logo=openjdk&logoColor=white)
![Rust 2024](https://img.shields.io/badge/-Rust%202024-orange.svg?logo=rust&logoColor=white)

# SMH

> **S**ubstrateVM **M**icrobenchmark **H**arness

**Write Java benchmarks. Build native executables. Measure wall time or simulated instructions.**

SMH brings JMH-style benchmarking to GraalVM Native Image, with an Elide-built
Micronaut CLI in `dev.elide.smh`.

[Get started](docs/GETTING_STARTED.md) · [Documentation](docs/README.md) ·
[Releases](https://github.com/elide-dev/smh/releases) · [Contributing](CONTRIBUTING.md)

## Why SMH?

[JMH](https://github.com/openjdk/jmh) is the standard starting point for Java
microbenchmarks, with a mature harness for JVM execution and JIT warmup. When
your application ships as a Native Image executable, you also need to understand
its performance under **SubstrateVM**, with the ahead-of-time compiler, build
options, and runtime that your application actually uses. A HotSpot benchmark
alone cannot answer that question.

SMH brings the familiar JMH benchmark model into a native build-and-run workflow.
It uses JMH's actual annotations and generates direct-call adapters and a
closed-world registry before native compilation. You can keep familiar benchmark
code while making the native artifact itself the subject of the experiment.
See the [supported API](docs/USAGE.md#java-api) when porting an existing suite.

The distinctive part is treating **the native build as part of the benchmark**:
compare `-O1`, `-O2`, and `-O3`, reuse Native Image layers with matching build
settings, and train/use PGO profiles where supported. Run wall-time measurements
or Linux Callgrind instruction/cache simulation against those artifacts, with
explicit measurement boundaries and retained raw observations. The Rust bootstrap
also lets a native host drive a benchmark compiled as a shared library.

For a faster edit/run cycle, the reusable Crema/Ristretto host loads benchmark
bytecode while keeping SMH's measurement machinery AOT compiled. A HotSpot/C2
execution mode runs the same generated workloads for comparison. Both reuse the
bytecode cache and retain their execution identity in results. See
[execution modes and build reuse](docs/AOT.md#cremaristretto-and-hotspotc2).

For an independent JVM reference, `smh jmh` runs the bundled JMH 1.37 harness on
HotSpot, optionally training and reusing a JDK 25 Leyden AOT cache. Results retain
JMH's original observations and render with SMH's histograms and comparison views.
[Compare with JMH and Leyden](docs/AOT.md#delegated-jmh-and-leyden); harness differences
remain explicit rather than being treated as interchangeable measurements.

## Run your first benchmark

From a checkout, with [mise](https://mise.jdx.dev), Python 3, GraalVM JDK 25
(`native-image` on `PATH`), and its host C compiler installed:

```sh
mise trust
mise install
mise run native
export PATH="$PWD/build:$PATH"

smh --help
smh build --config examples/build.json
smh run build/example/benchmarks --config examples/run.json
```

The `PATH` setting makes the built CLI available as `smh` in your current shell.
Results are saved to `build/results.json`; interactive runs show a summary, while
redirected output stays JSON. Prefer a packaged
SDK? Follow the [release installation guide](docs/GETTING_STARTED.md#install-a-release).

## Write familiar Java

```java
package bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class Sum {
  @Param({"16", "1024"})
  public int size;

  @Benchmark
  public long sum() {
    long total = 0;
    for (int i = 0; i < size; i++) {
      total += i;
    }
    return total;
  }
}
```

Returned values go through typed compiler blackholes. The
[complete ArraySum example](examples/src/dev/elide/smh/examples/ArraySum.java)
adds setup, modes, and timing annotations. See the
[supported annotation API](docs/USAGE.md#java-api) before porting a JMH suite.

## Choose what to measure

| Task | Command or guide |
| --- | --- |
| Fixed warmup and measurement | `smh run build/example/benchmarks --config examples/run.json` |
| Batch calibration and statistical stabilization | `smh run build/example/benchmarks --config examples/calibrated-run.json` |
| Instruction/cache simulation on Linux | [Callgrind guide](docs/SIMULATION.md) |
| Compare `-O1`, `-O2`, and `-O3` | [Build variants](docs/AOT.md#optimization-and-layers) |
| Train and use a PGO profile | [PGO walkthrough](docs/AOT.md#profile-guided-optimization) |
| Integrate through a native shared library | [Rust bootstrap](docs/AOT.md#shared-libraries) and [Myna ABI](docs/FFI.md) |

For example, build the same benchmark at two optimization levels and save each
run separately:

```sh
smh build --config examples/build.json -O1 --output build/O1
smh build --config examples/build.json -O3 --output build/O3

smh run build/O1/benchmarks --config examples/run.json
mv build/results.json build/O1-results.json
smh run build/O3/benchmarks --config examples/run.json
mv build/results.json build/O3-results.json
```

## Benchmark an Elide application

The [invoice application](examples/invoice-app/README.md) is a small nested Elide
project: build its application JAR, then compile and run a separate SMH benchmark
against it. It includes real CodSpeed simulation and walltime integration checks.

```sh
cd examples/invoice-app
elide run bench  # assemble app, build if inputs changed, run fresh measurements
```

Interactive runs use 24-bit color, a compact animated status line, and streaming results
with a prominent mean, aligned statistics, and nearby duration references. The sample collects 100 warmup iterations and 1,000 measured
iterations per input size, with iteration-score percentiles and variation.
Add `--compare baseline.json` for score deltas against a saved run. Use `--display plain` for JSON, or `--display tui` to force animation.

## Read the numbers honestly

The initial release supports single-worker AOT benchmarks. Raw samples, operation
counts, calibration probes, and stabilization stop reasons remain visible.
**Statistical stability does not establish measurement accuracy or JIT quiescence.**
Ordinary reports explicitly retain unqualified accuracy; compiler/CPU boundary
qualification and JIT/Crema support remain open.

Hosted tests cover JVM behavior on Linux, macOS, and Windows, on amd64 and arm64.
Native release binaries and full benchmark evidence currently cover **Linux amd64**.
See the [platform and assurance ledger](docs/ASSURANCE.md) for the exact scope.

Licensed under [Apache-2.0](LICENSE-APACHE) or [MIT](LICENSE-MIT), at your option.
