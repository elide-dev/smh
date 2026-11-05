<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

![SMH rabbit and stopwatch](assets/smh-social-banner.png)

# SMH documentation

Start with a working benchmark, then choose the measurement and build settings
you need. Commands use the repository root as their working directory unless a
guide says otherwise.

| I want to… | Read |
| --- | --- |
| Install SMH and run a Java benchmark | [Getting started](GETTING_STARTED.md) |
| Measure startup or sustained soak, with mode-specific charts | [Experiment modes](EXPERIMENTS.md) |
| Configure parameters, timing, and stabilization | [Running benchmarks](USAGE.md) |
| Build with layers, optimization levels, or PGO | [AOT builds](AOT.md) |
| Compare against JMH and HotSpot Leyden | [Delegated JMH](AOT.md#delegated-jmh-and-leyden) |
| Measure instructions and cache events | [Callgrind simulation](SIMULATION.md) |
| Understand exactly what is measured | [Measurement contract](MEASUREMENT.md) |
| Assess supported platforms and evidence | [Assurance ledger](ASSURANCE.md) |
| Integrate Rust and SubstrateVM | [Myna ABI](FFI.md) |
| Run coverage, fuzzing, sanitizers, and compiler checks | [Validation](VALIDATION.md) |
| Contribute a change | [Contributing](../CONTRIBUTING.md) |
| Work on this repository as a coding agent | [Agent instructions](../AGENTS.md) |
| See planned capabilities | [Roadmap](../ROADMAP.md) |

## Runnable examples

The [examples directory](../examples) is the source of truth for copyable
configurations:

- [Invoice application](../examples/invoice-app/README.md): a nested Elide project,
  packaged application JAR, native SMH benchmark, and real CodSpeed integration.
- [ArraySum.java](../examples/src/dev/elide/smh/examples/ArraySum.java): parameters,
  setup, average time, and throughput.
- [build.json](../examples/build.json) and [run.json](../examples/run.json): an O2
  layered build and fixed-count wall measurements.
- [calibrated-run.json](../examples/calibrated-run.json): bounded batch selection
  and adaptive warmup/measurement.
- [simulation-build.json](../examples/simulation-build.json) and
  [simulation-run.json](../examples/simulation-run.json): Callgrind-compatible
  compiler settings and a fixed invocation budget.

## Artwork

The supplied rabbit artwork is retained in [assets](assets):
[repository banner](assets/smh-repo-banner.png),
[social banner](assets/smh-social-banner.png),
[square artwork](assets/smh-repo-sq.png), and
[transparent icon](assets/smh-repo-icon.png).
