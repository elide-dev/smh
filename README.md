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

**Write Java benchmarks. Build native executables. Measure wall time or simulated instructions.**

SMH is an AOT benchmark harness for GraalVM Native Image. It uses JMH annotations,
generates direct benchmark calls, and provides an Elide-built Micronaut CLI in
`dev.elide.smh`. Builds support optimization levels, reusable Native Image layers,
and PGO where the compiler supports them.

[Get started](docs/GETTING_STARTED.md) · [Documentation](docs/README.md) ·
[Releases](https://github.com/elide-dev/smh/releases) · [Contributing](CONTRIBUTING.md)

## Run your first benchmark

From a checkout, with [mise](https://mise.jdx.dev), Python 3, GraalVM JDK 25
(`native-image` on `PATH`), and its host C compiler installed:

```sh
mise trust
mise install
mise run native

build/smh build --config examples/build.json
build/smh run build/example/benchmarks --config examples/run.json
```

Results are printed as JSON and saved to `build/results.json`. Prefer a packaged
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
| Fixed warmup and measurement | `build/smh run build/example/benchmarks --config examples/run.json` |
| Batch calibration and statistical stabilization | `build/smh run build/example/benchmarks --config examples/calibrated-run.json` |
| Instruction/cache simulation on Linux | [Callgrind guide](docs/SIMULATION.md) |
| Compare `-O1`, `-O2`, and `-O3` | [Build variants](docs/AOT.md#optimization-and-layers) |
| Train and use a PGO profile | [PGO walkthrough](docs/AOT.md#profile-guided-optimization) |
| Integrate through a native shared library | [Rust bootstrap](docs/AOT.md#shared-libraries) and [Myna ABI](docs/FFI.md) |

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
