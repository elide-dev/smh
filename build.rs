// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
fn main() {
    let target = std::env::var("TARGET").expect("Cargo supplies TARGET");
    assert!(
        [
            "x86_64-unknown-linux-gnu",
            "aarch64-unknown-linux-gnu",
            "x86_64-apple-darwin",
            "aarch64-apple-darwin",
            "x86_64-pc-windows-msvc",
            "aarch64-pc-windows-msvc",
        ]
        .contains(&target.as_str()),
        "Unsupported SMH native ABI target: {target}"
    );
    println!("cargo:rustc-env=SMH_ABI_TARGET={target}");
    println!("cargo:rerun-if-changed=abi/smh.seam");
    println!("cargo:rerun-if-changed=abi/generated");
}
