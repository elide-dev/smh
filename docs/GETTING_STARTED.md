<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Getting started

Build benchmarks with a **GraalVM JDK 25** that provides `native-image`, plus its
supported host C compiler/linker. The SDK CLI also needs Java 25. Native Image
capabilities such as layers and PGO depend on the compiler distribution.

## Install a release

Download the SDK from [GitHub Releases](https://github.com/elide-dev/smh/releases).
Version `0.1.0` is source-only; packaged downloads start with `0.1.1`.

Using the GitHub CLI on Linux or macOS:

```sh
gh release download v0.1.1 --repo elide-dev/smh \
  --pattern 'smh-0.1.1-*.zip' --pattern SHA256SUMS --dir smh-download
cd smh-download

# Linux; on macOS use: shasum -a 256 -c SHA256SUMS
sha256sum --check SHA256SUMS
unzip smh-0.1.1-sdk.zip -d ../smh-sdk
cd ..

java -cp 'smh-sdk/smh.jar:smh-sdk/lib/*' dev.elide.smh.cli.Main --help
```

Both ZIPs are downloaded so the checksum file can be verified in full. On Linux
amd64, extract `smh-0.1.1-linux-amd64.zip` instead to get the native `smh` CLI and
`smh-bootstrap` as well as the SDK. Keep `smh.jar`, `smh-runtime.jar`, and `lib/`
together: the CLI needs them when compiling benchmarks.

On Windows, extract the SDK ZIP and use a semicolon in the Java classpath:

```powershell
java -cp 'smh-sdk/smh.jar;smh-sdk/lib/*' dev.elide.smh.cli.Main --help
```

The six-host JVM test matrix covers the portable Java path. Full native benchmark
qualification beyond Linux amd64 remains open; see [platform evidence](ASSURANCE.md).

## Create a benchmark

In a new working directory, create `src/bench/Sum.java`:

```java
package bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
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

Place the extracted `smh-sdk/` beside `src/`, then create `build.json`:

```json
{
  "home": "smh-sdk",
  "sources": ["src"],
  "output": "build/benchmarks",
  "optimization": "2",
  "layers": true
}
```

Create `run.json`:

```json
{
  "include": "bench.Sum",
  "params": { "size": ["16", "1024"] },
  "output": "results.json"
}
```

Build and run:

```sh
java -cp 'smh-sdk/smh.jar:smh-sdk/lib/*' dev.elide.smh.cli.Main \
  build --config build.json
java -cp 'smh-sdk/smh.jar:smh-sdk/lib/*' dev.elide.smh.cli.Main \
  run build/benchmarks/benchmarks --config run.json
```

On Windows, use `;` in both classpaths and `build/benchmarks/benchmarks.exe`.
A compiler without layer support requires `--no-layers` on the build command.
With the native distribution, replace the Java launcher with `smh-sdk/smh`.
SMH prefers Elide when installed; otherwise it uses `JAVA_HOME`, then the JVM
Java home. To choose a JDK explicitly, add `--java-home /path/to/graalvm` to the
build command. SDK users do not need Elide or Rust.

The result includes one case for each parameter value. Inspect raw observations
and validity fields alongside the score; see [reading results](USAGE.md#reading-results).
This small example demonstrates the API, not a general compiler qualification.

## Build SMH from source

Install [mise](https://mise.jdx.dev) and Python 3, then run:

```sh
git clone https://github.com/elide-dev/smh.git
cd smh
mise trust
mise install
mise run native

build/smh --help
build/smh build --config examples/build.json
build/smh run build/example/benchmarks --config examples/run.json
```

`mise.toml` pins Elide and Rust. `mise run build` assembles the SDK without
building the native CLI; `mise run native` additionally needs GraalVM and a host
C compiler. From a mise-activated shell, the development Java launcher is:

```sh
java -cp 'build/smh.jar:build/lib/*' dev.elide.smh.cli.Main --help
```

For tests, formatting, and development constraints, see [Contributing](../CONTRIBUTING.md).
