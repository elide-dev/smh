<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Sanitizer evidence

Run `mise run test-rust-asan` on a Linux amd64 or arm64 host. The command installs
the pinned Rust nightly from `tools/dependencies.toml`, builds into the isolated
`target/sanitizer-address` directory, and creates a fresh evidence directory under
`build/rust-asan`. It requires the pinned nextest installation from `mise.toml`
and the host GCC linker. Pass `--output` to `tools/rust_sanitizer.py` to select a
new evidence directory.

The bootstrap, libloading dependency, Rust test harness, and dynamically compiled
test providers use Rust AddressSanitizer instrumentation. The executable exports
its sanitizer runtime symbols for the loaded providers. The profile first checks
clean startup, then loads a deliberately faulty provider through the production
bootstrap and generated ABI. Three providers separately perform an out-of-bounds
heap read, a heap use-after-free, and a deliberate 123-byte leak. Acceptance
requires each specific ASan/LSan diagnostic and the configured sanitizer exit
code, 86. Loader failure, an arbitrary crash, and a
successful process are not passing negative controls.

Only after all three controls pass does nextest run the loader, ABI fingerprint,
argument, lifecycle, and teardown tests. Every required test must run, with no
JUnit errors, failures, or skips. Evidence retains commands, compiler identity,
ASan options, executable/provider hashes, the actual sanitizer diagnostic,
test logs, and JUnit XML. A failed step leaves its logs but no passing evidence
manifest. Linux leak detection is enabled alongside ASan, with its own required
negative control at process exit. The verifier itself has regression tests for
false evidence, including crashes and wrong sanitizer diagnostics.

The Rust standard library is not rebuilt in this profile. Neither SVM managed
objects nor JIT-generated code are instrumented by it. The test providers are
Rust fixtures, not real SVM libraries. This profile uses the pinned Rust
compiler's sanitizer runtime; it does not qualify elide-dev/toolchain or
elide-dev/graal instrumentors. Separate fork-specific controls, real SVM
instrumentation, TSan, UBSan, MSan/HWASan where applicable, and broader native host
qualification remain tracked in ROADMAP.md.

The quality workflow runs this profile on Linux amd64 and arm64, archives failed
and successful evidence, and sends JUnit reports to Codecov. [Hosted quality checks](https://github.com/elide-dev/smh/actions/runs/41938178623)
passed on both Linux architectures.

See the [Rust sanitizer documentation](https://doc.rust-lang.org/unstable-book/compiler-flags/sanitizer.html)
for instrumentation scope, supported targets, and standard-library considerations.

## Miri lifecycle interpretation

`mise run test-miri` installs the pinned Miri/Rust source components and runs
the compatible Rust unit tests with strict provenance, Stacked Borrows and Tree
Borrows, and seeds 0 and 1. A clean control must succeed and a deliberate
use-after-free must produce Miri's undefined-behavior diagnostic before each
test run. The default target is the compiler host; use
`python3 tools/rust_miri.py --target aarch64-unknown-linux-gnu` to interpret the
ARM target. Results preserve commands, compiler identity, model/seed, logs and
JUnit cases, and require the complete compatible test inventory.

Dynamic loading has a separate native-only unit test and the existing native
integration suite. Miri uses Rust callbacks in place of a loaded SVM library.
Cross-interpretation checks Rust target semantics; it is not execution on ARM
hardware, an ABI calling-convention proof, or native measurement qualification.
Passing these finite executions does not establish soundness for all callers or
all possible executions. See the [Miri documentation](https://github.com/rust-lang/miri)
for its models and limitations.
