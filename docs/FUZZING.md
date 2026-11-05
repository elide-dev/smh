<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Coverage-guided fuzzing

Run `mise run fuzz-callgrind` for 10,000 inputs, or
`python3 tools/fuzz_callgrind.py --runs 100000 --seed 534948`.
The tool assembles the Elide project, freezes its JAR, compiles the fuzz targets,
and verifies SHA-256-pinned Jazzer artifacts from Maven Central. Jazzer is a
test tool, not a runtime dependency of SMH.

The Callgrind target exercises the production Callgrind summary parser with raw
mutated input. Documented validation errors are expected; other failures escape.
A second oracle constructs valid summaries from arbitrary unsigned byte strings
and checks exact BigInteger counters and omitted trailing zeros. Oracle failures
are never caught as expected parser rejections.

Before the campaign, a deliberately broken target must produce a finding and
save a crash input. That saved input is replayed and must reproduce the same
specific assertion. These expected control artifacts share the retained artifact
directory and are listed separately in the evidence manifest. Production-target
failures stop the campaign without a passing manifest.

Each run retains its initial and evolved corpus, crash inputs, Java reproducers,
logs, seed, command arguments, target-source hashes, runtime JAR hash, Java
identity, and parser coverage report. The campaign requires completed fuzzing
with nonzero coverage feedback and observed instrumentation of the selected production parser.
A zero-exit process without that evidence is insufficient. The checked-in corpus
contains exact counters above 64 bits, invalid negative counters, cache metadata,
and duplicate cache descriptors.

The hardened Linux amd64/arm64 workflow runs 10,000 inputs for changes and
1,000,000 inputs on its weekly schedule. It archives evidence even on failure.
JUnit reports represent the campaign as one test, not one test per generated
input. [Hosted campaigns](https://github.com/elide-dev/smh/actions/runs/41940035267)
pass on both architectures.

Retain a real finding's saved input and reproducer, minimize it using Jazzer's
libFuzzer crash-minimization flags, fix the defect, and add the minimized input
to the regression corpus. The exact launch arguments are in `commands.json`.
Do not suppress an unexpected exception simply to make the campaign pass.
Automatic minimization and CLI, build configuration, metadata/state-graph, and FFI
targets remain open. This target's coverage is separate from whole-project
JaCoCo gates and is not proof of semantic correctness.

See [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) for its
instrumentation, corpus, and reproduction interfaces.


## Run configurations

`mise run fuzz-run-config` selects the second target. For longer campaigns use
`python3 tools/fuzz_callgrind.py --target run-config --runs 100000`.
It exercises the filesystem-free `RunConfig.fromJson` API and uses independent
valid-input construction to check exact nanosecond durations, simulation work
budgets, batch sizes, and parameter strings. Only JSON syntax, schema/validation,
and exact-number arithmetic failures are expected in the raw-input path;
unexpected exceptions fail the campaign. The valid-input oracle catches none.

Run configuration fields now enforce their JSON types: counts are numbers,
string fields and parameter values are strings, and containers must have the
declared shape. Quoted numbers and scalar-to-string coercion are rejected.
This describes the schema contract; it does not claim stricter text grammar than
the Gson parser used by the file entrypoint.

Jazzer's regex-injection hook is disabled only for this target because `include`
intentionally accepts a complete user-supplied regex. Invalid regex syntax still
fails validation; coverage instrumentation and other hooks remain enabled.
The initial hook finding and reproducer are retained under
`build/fuzz-run-config/20261104T230944005906Z/`. This is an intentional API
operation, not an unexpected exception suppressed by the target.

Both targets run in the Linux amd64/arm64 workflow matrix, with separate corpora,
coverage evidence, hashes, and JUnit campaign results.
