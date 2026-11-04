<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Instruction/cache simulation

The first simulator backend is real Callgrind on Linux. `MeasurementBackend`
selects wall time or simulator events explicitly. The simulation path executes
an exact invocation budget without reading a wall clock. It runs each parameter
case once per iteration, independently of JMH time/throughput annotations.

```sh
mise run native
build/smh build --config examples/simulation-build.json
build/smh simulate build/simulation/benchmarks \
  --config examples/simulation-run.json --output build/simulation-run-1
```

Native Image, its host compiler, and Valgrind must be installed on the Linux
worker. The output directory must be new. `--valgrind` selects a simulator
executable. Other hosts receive an explicit unsupported error; no wall-clock
fallback is performed. Codspeed and remote-worker orchestration remain open.

The simulator owns its manifest output: use CLI `--output`, and omit the run
configuration `output` field. Benchmark stdout is retained separately in
`benchmark.stdout.log`; the manifest is written directly to `execution.json`.
Both requested and effective run configurations are retained and hashed.

## Work and boundaries

`invocationsPerIteration` is required and positive. `warmupIterations` and
`measurementIterations` override annotation counts; otherwise those counts apply.
Reported operations equal invocations multiplied by `@OperationsPerInvocation`,
with overflow checked before trial setup. Wall-duration overrides and JMH mode
overrides are rejected in simulation. Online simulator convergence is not yet
implemented; simulation currently rejects convergence policies explicitly.

Callgrind collects only inside the non-inlined `SimulationRunner.measureBatch`
method and dumps after each return. The selected exact demangled symbol is
recorded in the launch command. Build with `-g` and `-H:-DeleteLocalSymbols`, and
keep image/layer libraries and available debug sidecars together. The example
also selects `-march=compatibility`; a simulator must support the binary's ISA.
Optimization/PGO/layer options retain their usual build meanings.

Trial and iteration fixtures, warmup, observers, and reporting are outside this
function. Invocation-fixture cases are rejected because a batch cannot exclude
their per-invocation work while preserving their lifecycle. Collection is per
thread; unrelated threads must have zero collected events. The region includes
the batch loop, invocation calls, and synchronous runtime work on the benchmark
thread. These costs are reported without subtracting an estimated overhead.

Instrumentation stays active while collection is off, including warmup. The
declared cache model is I1/D1 32 KiB, 8-way, 64-byte lines; LL 8 MiB, 16-way,
64-byte lines. Branch simulation is enabled. This is a declared simulator model,
not a reconstruction of the host CPU. Cache/predictor observations can vary even
when instruction counts repeat; every sample remains in the report.

## Results and validity

Direct benchmark execution with this backend emits a `pending-counter-import`
manifest and no numeric performance metric. `smh simulate` launches Callgrind,
captures that manifest, checks process success, and imports the corresponding
dumps. It refuses missing/extra/noncontiguous regions, duplicate identities,
multiple measured threads/processes, changed event/cache metadata, nonzero events
outside the region, contradictory totals, and malformed or negative counts.
Failed runs retain their raw evidence and do not produce `results.json`.

The final report records simulator version, architecture, explicit cache model,
command, binary/config hashes, and raw dump paths/hashes. When present, the build
sidecar is included with its hash; its binding to the executable is explicitly
unverified. PGO instrumented images are labeled `instrumented-validation`, and
missing build metadata is labeled `unknown-build-exploration`. These results must
not be combined with ordinary uninstrumented measurements. Each observation retains
exact decimal-string event counts and operation counts. Instruction-per-operation
display uses DECIMAL128; the exact numerator and denominator are also present.
No simulator count is labeled as elapsed time. `instructionRepeatability` reports
`identical`, `varies`, or `not-assessed`; it is not a convergence or confidence
claim. `accuracy` remains `unqualified` and compiler-sink qualification remains
`unverified` in ordinary reports.

`mise run test-simulation` builds monolithic and layered O2 fixtures, checks
repeated runs, increased warmup, substantial setup/teardown work, and the affine
instruction-count relation across three work budgets. It also disables collection
and enables unbounded collection deliberately; both must be rejected by the
production importer. This is concrete region evidence on the executing host,
not general CPU ordering proof or a qualification of every compiler/architecture.

See the [Callgrind manual](https://valgrind.org/docs/manual/cl-manual.html) for
function collection controls, per-thread dumps, cache modeling, and architecture
limitations. The raw call graphs remain available for independent inspection.
