<!-- SPDX-FileCopyrightText: 2026 SMH contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 OR MIT -->

# Rust / SVM boundary

`abi/smh.seam` is the declaration source for SMH's current native bootstrap ABI.
Myna 0.7.0 is pinned by revision and source-archive SHA-256 in
`tools/dependencies.toml`. SMH downloads it into ignored build output and builds
the generator with Elide. No generator dependency is added to the runtime.

`mise run generate-abi` reproduces C, Rust, Java, LLVM, and canonical JSON outputs
for six targets. `mise run check-abi` rejects modified, missing, or extra output.
The annotation processor embeds the selected generated Java entry points, selected
by `smh.target`; the native builder supplies the host target. Every target's Java
entry points are compiled by the processor tests. Rust selects the same target's
generated function-pointer types during its build.

## Functions and lifetimes

| Function | Contract |
| --- | --- |
| `graal_create_isolate` | SDK operation; zero means success and must produce nonnull isolate/thread handles. Rust passes default creation parameters. |
| `smh_abi_tag(thread, index)` | Returns one of four 64-bit words of Myna's SHA-256 ABI fingerprint. Every word is checked before benchmark entry. |
| `smh_run(thread, config)` | Synchronous entry on the creating thread. Configuration is a borrowed, nonnull, NUL-terminated UTF-8 path; empty selects defaults. Zero means success. |
| `graal_tear_down_isolate` | SDK operation; attempted exactly once after successful creation with a usable thread, including fingerprint/entry failures. |

Rust resolves all symbols before creating an isolate. A lifetime bound retains
the library until every function call and teardown has finished. Configuration
storage remains live throughout entry; no pointer ownership transfers across the
boundary. A nonzero create status does not authorize use of the output handles.
A null thread after reported success fails explicitly because teardown cannot be
called safely. A valid thread with a null isolate is cleaned up and rejected.

The benchmark's primary status survives a second teardown failure, and diagnostics
include both errors. Process statuses normalize to 1..255 for nonzero native
statuses. Libraries are trusted executable input; a matching fingerprint detects
accidental declaration drift and does not authenticate a library. Additional
thread attachment and asynchronous callbacks are outside this ABI.

## Target adaptation and evidence

Upstream Myna 0.7.0 describes Linux and Darwin amd64/arm64. SMH applies a narrow,
anchor-checked build-time adaptation for Windows amd64/arm64. Its shape guard
permits only the i32/i64 and pointer signatures used here, with no aggregates,
callbacks, or optimizer facts. It does not establish general Windows support in
Myna. No upstream source is vendored. The pinned upstream tree did not provide a
license file when inspected; the release dependency/license inventory remains an
open assurance item.

`tools/verify_abi.py` compares Clang's lowered declarations for all six C headers
with Myna's LLVM signatures. `--svm-header HEADER --svm-target TARGET` additionally
checks an actual Native Image output header. C pointer typedefs differ between
the SDK and the opaque generated seam, so the comparison uses lowered ABI types.
No ThinLTO contracts are applied to objects by this integration.

Local Linux amd64 evidence includes a real O2 layered shared image invoked by the
Rust bootstrap, with all example cases completed after the fingerprint check.
Ten Rust tests independently cover symbol lookup failures before creation,
fingerprint mismatch before entry, creation/null-handle failures, combined
entry/teardown failures, argument validation, and full-width fingerprint handling.
The controllable Rust test library is a failure-injection fixture, not SVM.
Native execution on other hosts, sanitizer/fuzzing evidence, simulator region
functions, and additional thread attachment remain separate roadmap items.
