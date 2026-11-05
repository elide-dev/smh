<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Measurement contract

## Independent dimensions

Execution (AOT, JVM/JIT, Crema, guest runtime), measurement (wall time,
instruction/cache simulation), optimization, PGO, architecture, OS, and compiler
qualification are independent dimensions. Backends report capabilities; missing
capabilities produce explicit unsupported outcomes. They are never silently
replaced by another measurement method.

The runtime-loaded hosts use SMH's generated benchmark suite contract. Crema
keeps the measurement loop AOT compiled while its adapters and workload classes
are runtime loaded; HotSpot/C2 can compile both harness and workload. The
AOT-to-runtime-loaded call boundary is therefore an explicit part of the Crema
execution model. All hosts retain fixture ordering and integer operation counts.
Execution metadata records compiler selection and host/payload identity; it
does not establish that a particular method compiled or that JIT activity stopped.
Validation records compilation diagnostics separately and keeps quiescence unknown.

Harness identity is another comparison dimension. Delegated JMH is not currently
implemented. Future JMH results must retain JMH's own forks, warmup, sinks, raw
observations, and timing semantics, separately from SMH's result schema.

Wall measurements use a monotonic elapsed-time source and integer operation
counts. Simulation measurements use native simulator event counts and a declared
work budget. Neither simulator duration nor a normalized instruction score is
hardware wall time.

## Region state machine

The logical sequence is discovery → trial setup → calibration → warmup → measured
iterations → trial teardown → analysis/reporting. PGO training is a separate run
purpose. For each iteration, iteration setup precedes region entry; region exit
precedes iteration teardown. Only operations deliberately included by the selected
policy occur between the two borders. Reporting and diagnostic logging stay out.

Invocation fixtures need their own explicit policy: either they belong to a
scenario's measured work, or individually delimited invocations exclude them and
carry their additional timer/border uncertainty. A result records the policy.
Batching cannot move fixtures across their declared lifecycle.

Wall runs accept a positive integer `wallBatchSize` (default 1). A value greater
than one brackets that many direct benchmark invocations with one clock pair.
The same batch size applies to warmup and measurement; single-shot mode executes
one complete batch per iteration. Counts include `@OperationsPerInvocation`,
and scores normalize by the actual total operations. The batch loop and dispatch
are part of the measured interval; no estimated overhead is subtracted. Runs
report both batch sizes and `fixed-batch-without-invocation-fixtures` as their
timing policy. Invocation-fixture benchmarks reject batches larger than one
before trial setup. Custom adapters must accurately declare invocation fixtures
through `hasInvocationFixtures()`.

Optional `batchCalibration` selects a batch before warmup. It doubles from one
up to explicit batch/attempt limits, retaining every probe. Every probe in an
attempt must reach the target duration; exhaustion is reported rather than
hidden. Iteration fixtures surround probes outside the clock pair. Calibration
is incompatible with invocation fixtures, simulation, or an explicit batch greater than one.
This measures probe duration, not timer resolution or empty-region overhead;
it neither subtracts overhead nor detects JIT quiescence. Increasing
batch size can change cache, allocation, and optimization behavior, so reports
with different batch sizes should remain distinguishable. Compiler/CPU border
qualification of the new batch path remains open; ordinary results retain
`accuracy=unqualified`.

Once region entry succeeds, exactly one exit is attempted, including when the
benchmark throws. Once a fixture scope is entered successfully, its cleanup is
attempted in reverse dependency order. Primary errors survive; cleanup errors
are retained as suppressed errors. Generated adapters attempt every teardown even
when an earlier teardown throws: the first failure is primary and later distinct
failures are suppressed in cleanup order. Throwing the same object again does not
attempt illegal self-suppression. Iteration/trial blackhole clearing still runs.
Partial iterations do not become valid samples.

Generated setup treats each state as a cleanup unit. A state is complete only
after all of its setup methods for the requested lifecycle level return.
If a later state fails setup, previously completed states are torn down in
reverse order. The failing state and states not yet entered are not torn down;
a setup method must release its own partially acquired resources before throwing.
This policy applies at trial, iteration, and invocation levels. Cleanup failures
are attached directly to the original setup exception, preserving identity and
avoiding a suppression cycle when cleanup rethrows that same exception.

## Compiler and CPU obligations

A blackhole must keep the intended computation observable to the selected
compiler without fabricating realistic work through accidental allocation or
locking. Returning a value and discarding it is insufficient. A fallback volatile
sink is not proof that a compiler intrinsic was installed.

The compiler ordering contract prevents intended work moving outside measurement
borders and excludes forbidden hoisting/sinking/CSE. Runtime ordering, CPU memory
ordering, and clock serialization are separate obligations. Qualification uses
positive and deliberately broken controls, compiler graphs and disassembly, and
dynamic measurements on actual amd64/arm64 hardware. Compilation success alone
does not establish any of these properties.

Qualification is tied to artifact hashes, compiler build/options, execution mode,
PGO/profile, layer identities, architecture, and helper ABI. Unknown profiles may
run experimentally, but cannot receive an evidence-backed accuracy claim.

## Stabilization

Warmup and measurement use predeclared bounded policies: minimum observations,
window sizes, acceptable variation/drift, consecutive passing windows, and a hard
sample limit. Every observation and stop reason is retained. Independent hard
time/work limits remain future obligations.

Convergence requires the configured checks, including trend/dispersion tests. A
constant series, a small coefficient of variation, or reaching a maximum count
does not prove useful benchmark work survived optimization. Statistical stability
does not establish JIT compilation/deoptimization quiescence. A JIT event provider
must independently report supported, quiescent, active, or unknown status.

Adaptive stopping changes the statistical procedure. Confidence intervals and
comparison tests must account for that procedure or explicitly decline the
corresponding inference. Never keep rerunning until a favorable sample appears.

## Report validity

Results retain raw samples, sample order, work counts, units, timer/counter
calibration, warmup history, stopping rules, environment, toolchain/artifact
identities, and simulator settings. Validity, statistical stability, JIT
quiescence, precision, and platform qualification are distinct fields.

Instrumented PGO, sanitizers, fuzzing, and coverage runs are validation/training
artifacts. They are never silently mixed into uninstrumented performance results.
Unsupported, unqualified, unstable, or failed runs must be obvious to people and
machine consumers.
