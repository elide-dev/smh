<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Working on SMH

SMH is an Elide-built Micronaut/Picocli CLI and AOT benchmark harness. Java lives
under `dev.elide.smh`; Rust provides the native bootstrap. Use this file for
repository work and [docs/README.md](docs/README.md) for product behavior.

## Find the right code

| Location | Responsibility |
| --- | --- |
| `src/main/java/dev/elide/smh/cli/` | CLI, build planning, Callgrind collection/import |
| `src/main/java/dev/elide/smh/processor/` | JMH annotation processing and direct-call adapters |
| `src/main/java/dev/elide/smh/runtime/` | Timing, simulation, calibration, stability, lifecycle |
| `src/test/`, `tests/` | JVM, native, FFI, fuzz, and orchestration tests |
| `src/*.rs`, `build.rs`, `abi/` | Rust bootstrap and Myna seam/generated bindings |
| `tools/` | Portable build and verification orchestration |
| `examples/` | Runnable Java and JSON examples |
| `mise.toml`, `elide.pkl` | Pinned tools and Elide project/tasks |

## Work and verify

```sh
mise trust
mise install
mise exec -- rustup component add --toolchain 1.91.1 rustfmt clippy
mise run build
mise run lint
mise run test-tools
mise run coverage
mise run coverage-rust
```

Use the targeted native/fuzz/ABI checks in [docs/VALIDATION.md](docs/VALIDATION.md)
when changing those boundaries. Run SDK/JVM packaging and native compilation
sequentially: functional tests can replace JARs while a compiler is reading them.
Do not hand-edit `abi/generated/`; change the seam/generator and run
`mise run generate-abi`, then `mise run check-abi`.

## Invariants

- Keep timing, operation counts, units, overflow checks, fixture ordering, and
  failure cleanup explicit. Retain raw observations and non-success stop reasons.
- Keep logging/reporting outside measured work. A returned value or a passing
  timing test alone does not prove that the compiler preserved the benchmark.
- Keep statistical stability, JIT quiescence, and accuracy qualification separate.
  Unsupported backends/platforms must fail clearly; no silent fallback.
- Preserve compiler/options/profile/layer identity. Instrumented training and
  validation runs must not masquerade as ordinary performance results.
- Keep full ABI fingerprint checks and library/configuration lifetimes intact.
  Test providers are not real SVM evidence.
- Do not lower coverage gates, exclude production paths, or treat skipped tests,
  missing reports, stub artifacts, or unexecuted workflows as passing evidence.

For timer, blackhole, statistics, FFI, or unsafe-code changes, update
[docs/ASSURANCE.md](docs/ASSURANCE.md) with the actual evidence and limits.
[ROADMAP.md](ROADMAP.md) tracks stable feature IDs; a task is complete only when
its stated evidence exists.

Use pinned formatters, SPDX `Apache-2.0 OR MIT` headers, and signed-off commits.
Follow [CONTRIBUTING.md](CONTRIBUTING.md) for review and test expectations.
Release Please owns version bumps. Releases must remain drafts until assets are
uploaded and verified. Never delete/reuse a published immutable release tag.
