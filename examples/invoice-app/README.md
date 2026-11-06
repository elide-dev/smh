<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Build an application, then benchmark it

This is an independent Elide project. Its CLI totals invoices using integer cents
and checked arithmetic. Elide packages the application into `build/invoice-app.jar`;
SMH invokes the configured `elide run assemble` preparation command to build the
application, then compiles a separate JMH-style benchmark against that JAR.

Use the repository's pinned Elide, GraalVM JDK 25 with `native-image`, and a host
C compiler. The commands below run from a mise-activated shell. From the repository
root, build SMH once and put the CLI on your path:

```sh
mise run native
export PATH="$PWD/build:$PATH"
cd examples/invoice-app
```

To run the application by itself, assemble it first:

```sh
elide run assemble
elide java -- -jar build/invoice-app.jar
# Total: 6497 cents
```

Build the application and benchmark, then run both input sizes in one step:

```sh
elide run bench
# Or directly:
smh bench
```

The build configuration's `prepare` command runs once before SMH validates the
application JAR or checks the benchmark cache. Elide incrementally compiles the
application and packages a deterministic JAR. SMH hashes build inputs and verifies
cached artifacts before reuse. Missing or changed application outputs are rebuilt
before measurement; unchanged bytes preserve the benchmark cache. Every invocation
runs fresh measurements. Use `smh bench --rebuild` to force benchmark compilation.
One line reports the cache outcome, for example `Cache hit · … · build inputs
unchanged · 1.8 s` or `Built · … · changed: env:CFLAGS · 53 s`; a new shell session
or unrelated environment variable never forces a rebuild. Preparation output, full
compiler logs, and cache decisions are saved in the printed log locations. Use `--verbose` to replay build diagnostics before the
benchmark report. A failed preparation stops the run before measurement.

Each input size gets **100 warmup iterations and 1,000 measured iterations**, with
a 1 ms target per iteration. These are iteration scores, not 1,000 individual
invocations; each iteration can execute many operations. Runtime overhead can make
the total longer than the nominal 1.1 seconds per case.

Each case renders as soon as it completes: the median with its spread, the mean,
min/p99/iteration count, then a histogram bounded at p99 (or relative baseline bars
with a spread band and a summary verdict). Microseconds display as µs. VM/JDK details appear once at the end, and log/report
paths are relative to this project. Unassessed phases are omitted; non-success
stopping reasons remain visible. Full phase metadata remains in JSON.
There are no warmup sparklines or inferred JIT curves.

The run configuration saves all observations to `build/results.json`. Use
`--display plain` for JSON, or force animation on stderr while saving stdout:

```sh
smh bench --display tui > build/run.json
cp build/results.json baseline.json
smh bench --compare baseline.json
```

`InvoiceBenchmark.total()` calls `InvoiceApp.total()` from the packaged application.
Setup constructs inputs outside the measured region. Returned totals go through
SMH's typed compiler blackhole. This demonstrates the application workflow; it
does not establish general measurement accuracy.

## Startup and soak

Try the compact mode-specific views; SMH assembles the application automatically:

```sh
smh bench --mode startup --launches 20 --output build/startup --display tui
smh bench --mode soak --duration 10s --output build/soak --display tui
```

Each output directory must be new. Both input sizes run. Startup uses fresh
processes and measures through the first result; `--until ready` selects setup
readiness and `--until exit` selects the full process lifetime. Soak measures each
size for the full duration after warmup. Histograms retain the distribution;
launch order and soak timelines retain history. See [Experiment modes](../../docs/EXPERIMENTS.md)
for simulation commands and exact measurement boundaries.

Both invoice sizes run in interleaved rounds and share colored distribution and
launch-order charts. The `lines=10` and `lines=1000` series update after each launch.
These are different workloads, so neither is called a winner.

The live view clears before the final startup report appears. Build/cache details stay in
`build.log` under the experiment directory; `--verbose` prints them before results.
The terminal rounds numbers for readability; JSON retains exact observations and
qualification metadata.

## CodSpeed: simulation and walltime

Install [CodSpeed CLI 5.6.3](https://codspeed.io/docs/cli). Run either instrument
against a native executable at a predictable path (build it explicitly first):

```sh
smh build --config smh-build.json
codspeed exec --skip-upload -m simulation -- ./build/benchmarks/benchmarks smh-fixed-work.json
CODSPEED_PERF_ENABLED=false codspeed exec --skip-upload -m walltime \
  --warmup-time 0 --max-rounds 3 -- ./build/benchmarks/benchmarks smh-fixed-work.json
```

The included `codspeed.yml` also supports `codspeed run --skip-upload -m simulation`
and `codspeed run --skip-upload -m walltime`. `--skip-upload` keeps these verification
runs local; omit it only when you intend to publish results to your CodSpeed account.
The integration test disables walltime profiling, so it collects real timings
without configuring kernel perf access.

These are **whole-process measurements**: startup, setup, 100 benchmark invocations,
and JSON reporting. The fixed-work configuration uses SMH's clock-free Callgrind
execution path. Its stdout is a `pending-counter-import` manifest, not a CodSpeed
score. CodSpeed's own artifacts contain its measurements; SMH does not import or
relabel them as per-operation results. The ordinary SMH wall benchmark above
measures a different region. Do not compare their numbers directly.

The sample retains native function symbols but intentionally omits `-g`: CodSpeed
5.6.3's Valgrind reader crashed on this GraalVM 25 build's DWARF information in local
validation. Function-level profiling remains possible; Java source-line profiling
is not qualified. Full custom CodSpeed region hooks remain a separate roadmap item.

From the repository root, reproduce the checks:

```sh
mise run test-invoice
mise run test-codspeed
```

The second command rebuilds the application and benchmark sequentially, then
requires real positive simulation counters and three real walltime rounds. Missing
CodSpeed, compiler failures, empty measurements, or instrument failures fail the test.
Commands, diagnostics, artifact hashes, and raw measurements are retained under
`build/invoice-tests/` and `build/codspeed-tests/`. CI runs the same check on Linux
amd64 and retains artifacts even on failure.

To compare whole-application startup, use command candidates with preparation
outside the measured launches. From this directory, with JDK 25 `java` on `PATH`:

```sh
smh --mode startup --launches 20 \
  --prepare elide run assemble \
  --candidate java -jar build/invoice-app.jar \
  --candidate java -Xint -jar build/invoice-app.jar
```

The second candidate uses interpreted execution as an illustration; it does not
configure a C2 benchmark. Both candidates run the same application through process
exit, including printing its total. This differs from the benchmark-method startup
boundary above. SMH runs preparation once before all launches, logs command output,
and compares the observed means. Failed launches stop the comparison; they are
not replaced by an automatic rebuild and retry. See
[interleaved command comparisons](../../docs/EXPERIMENTS.md#interleaved-command-comparisons).
