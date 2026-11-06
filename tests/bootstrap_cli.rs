// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
use std::{
    ffi::OsStr,
    path::{Path, PathBuf},
    process::{Command, Output},
};

static DIRECTORIES: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        // Tests run concurrently in one process; a clock alone collides on coarse timers.
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let serial = DIRECTORIES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let path =
            std::env::temp_dir().join(format!("smh-ffi-{}-{serial}-{nonce}", std::process::id()));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn bootstrap(args: &[&OsStr], environment: &[(&str, &str)]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_smh-bootstrap"))
        .args(args)
        .envs(environment.iter().copied())
        .output()
        .unwrap()
}

fn provider(directory: &Path, variant: &str) -> PathBuf {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let name = if cfg!(target_os = "windows") {
        format!("{variant}.dll")
    } else if cfg!(target_os = "macos") {
        format!("lib{variant}.dylib")
    } else {
        format!("lib{variant}.so")
    };
    let path = directory.join(name);
    let mut rustc =
        Command::new(std::env::var_os("SMH_TEST_RUSTC").unwrap_or_else(|| "rustc".into()));
    if let Ok(sanitizer) = std::env::var("SMH_TEST_SANITIZER") {
        assert_eq!(sanitizer, "address");
        rustc.arg("-Zsanitizer=address");
    }
    rustc.args([
        "--crate-type=cdylib",
        "--edition=2024",
        "-Cpanic=abort",
        "--cfg",
        variant,
    ]);
    if cfg!(target_os = "linux") {
        rustc.arg("-Clinker=gcc");
    }
    if cfg!(target_os = "macos") {
        rustc.arg("-Clinker=/usr/bin/cc");
    }
    let result = rustc
        .env(
            "SMH_TEST_ABI_RS",
            root.join("abi/generated")
                .join(env!("SMH_ABI_TARGET"))
                .join("seam.rs"),
        )
        .arg(root.join("tests/ffi/provider.rs"))
        .arg("-o")
        .arg(&path)
        .output()
        .unwrap();
    assert!(
        result.status.success(),
        "{}",
        String::from_utf8_lossy(&result.stderr)
    );
    path
}

#[test]
fn help_version_usage_and_loader_failures_have_observable_exit_codes() {
    for option in ["--help", "--version"] {
        let result = bootstrap(&[OsStr::new(option)], &[]);
        assert!(result.status.success());
        assert!(
            String::from_utf8(result.stdout)
                .unwrap()
                .contains("smh-bootstrap")
        );
    }
    for args in [
        vec![],
        vec![OsStr::new("a"), OsStr::new("b"), OsStr::new("c")],
        vec![OsStr::new("missing-native-library")],
    ] {
        let result = bootstrap(&args, &[]);
        assert_eq!(result.status.code(), Some(1));
        assert!(
            String::from_utf8(result.stderr)
                .unwrap()
                .contains("smh-bootstrap:")
        );
    }
    let directory = Directory::new();
    let invalid = directory.0.join("not-a-library");
    std::fs::write(&invalid, "invalid binary").unwrap();
    assert_eq!(
        bootstrap(&[invalid.as_os_str()], &[]).status.code(),
        Some(1)
    );
}

#[test]
fn partial_symbol_sets_are_rejected_before_creation_and_wrong_fingerprints_before_entry() {
    let directory = Directory::new();
    let log = directory.0.join("events.txt");
    for variant in ["omit_create", "omit_entry", "omit_tag", "omit_cleanup"] {
        let library = provider(&directory.0, variant);
        let result = bootstrap(
            &[library.as_os_str()],
            &[("SMH_TEST_EVENT_LOG", log.to_str().unwrap())],
        );
        assert_eq!(result.status.code(), Some(1));
        assert!(
            !log.exists(),
            "Symbol validation must finish before isolate creation"
        );
    }
    let library = provider(&directory.0, "wrong_tag");
    let result = bootstrap(
        &[library.as_os_str()],
        &[("SMH_TEST_EVENT_LOG", log.to_str().unwrap())],
    );
    assert_eq!(result.status.code(), Some(1));
    assert!(
        String::from_utf8(result.stderr)
            .unwrap()
            .contains("fingerprint mismatch")
    );
    assert_eq!(
        std::fs::read_to_string(log).unwrap(),
        "create\ntag0\ntag1\ntag2\ntag3\ncleanup\n"
    );
}

#[test]
fn native_entry_and_teardown_failures_keep_the_primary_status_and_unwind_once() {
    let directory = Directory::new();
    let library = provider(&directory.0, "complete");
    let log = directory.0.join("events.txt");
    for (entry, cleanup, expected) in [
        (0, 0, 0),
        (7, 0, 7),
        (7, 11, 7),
        (0, 11, 1),
        (-1, 11, 1),
        (999, 11, 255),
    ] {
        let result = bootstrap(
            &[library.as_os_str(), OsStr::new("config.json")],
            &[
                ("SMH_TEST_EVENT_LOG", log.to_str().unwrap()),
                ("SMH_TEST_ENTRY_STATUS", &entry.to_string()),
                ("SMH_TEST_CLEANUP_STATUS", &cleanup.to_string()),
            ],
        );
        assert_eq!(result.status.code(), Some(expected));
        assert_eq!(
            std::fs::read_to_string(&log).unwrap(),
            "create\ntag0\ntag1\ntag2\ntag3\nentry\ncleanup\n"
        );
        if cleanup != 0 {
            let stderr = String::from_utf8(result.stderr).unwrap();
            assert!(stderr.contains(&format!("benchmark returned status {entry}")));
            assert!(stderr.contains("teardown also failed: 11"));
        }
        std::fs::remove_file(&log).unwrap();
    }
}
