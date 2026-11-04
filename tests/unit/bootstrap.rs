// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
use super::*;
use std::{cell::RefCell, ffi::c_void};

#[derive(Default)]
struct Trace {
    create_status: i32,
    entry_status: i32,
    cleanup_status: i32,
    null_thread: bool,
    null_isolate: bool,
    wrong_tag: bool,
    calls: Vec<String>,
}

thread_local! { static TRACE: RefCell<Trace> = RefCell::new(Trace::default()); }

unsafe extern "C" fn create(
    _: *const c_void,
    isolate: *mut *mut c_void,
    thread: *mut *mut c_void,
) -> i32 {
    TRACE.with_borrow_mut(|trace| {
        trace.calls.push("create".into());
        if trace.create_status == 0 {
            // Opaque sentinel handles are only compared, never dereferenced by these stubs.
            unsafe {
                *isolate = if trace.null_isolate {
                    std::ptr::null_mut()
                } else {
                    std::ptr::dangling_mut::<u8>().cast()
                };
                *thread = if trace.null_thread {
                    std::ptr::null_mut()
                } else {
                    std::ptr::dangling_mut::<u8>().cast()
                };
            }
        }
        trace.create_status
    })
}

unsafe extern "C" fn tag(_: *mut c_void, index: i32) -> i64 {
    TRACE.with_borrow_mut(|trace| {
        trace.calls.push(format!("tag{index}"));
        i64::from(trace.wrong_tag && index == 2)
    })
}

unsafe extern "C" fn entry(_: *mut c_void, config: *const u8) -> i32 {
    TRACE.with_borrow_mut(|trace| {
        trace.calls.push(format!(
            "entry:{}",
            unsafe { CStr::from_ptr(config.cast()) }.to_str().unwrap()
        ));
        trace.entry_status
    })
}

unsafe extern "C" fn tear_down(_: *mut c_void) -> i32 {
    TRACE.with_borrow_mut(|trace| {
        trace.calls.push("cleanup".into());
        trace.cleanup_status
    })
}

fn execute(trace: Trace) -> (Result<i32, Failure>, Vec<String>) {
    TRACE.set(trace);
    let api = Api {
        create,
        entry,
        tag,
        tear_down,
        _library: std::marker::PhantomData,
    };
    let result = unsafe { api.execute(c"config.json", [0; 4]) };
    (result, TRACE.with_borrow(|t| t.calls.clone()))
}

#[test]
fn lifecycle_balances_and_preserves_statuses() {
    for status in [0, 1, 7, 255, 256, -1, i32::MAX, i32::MIN] {
        let (result, calls) = execute(Trace {
            entry_status: status,
            ..Trace::default()
        });
        assert_eq!(result.unwrap(), status);
        assert_eq!(
            calls,
            [
                "create",
                "tag0",
                "tag1",
                "tag2",
                "tag3",
                "entry:config.json",
                "cleanup"
            ]
        );
        assert_eq!(
            exit_status(status),
            if status == 0 {
                0
            } else {
                status.clamp(1, 255) as u8
            }
        );
    }
}

#[test]
fn creation_failure_and_null_handles_never_enter_the_benchmark() {
    let (failure, calls) = execute(Trace {
        create_status: 9,
        ..Trace::default()
    });
    assert!(
        failure
            .unwrap_err()
            .to_string()
            .contains("creation failed: 9")
    );
    assert_eq!(calls, ["create"]);
    let (failure, calls) = execute(Trace {
        null_thread: true,
        ..Trace::default()
    });
    assert!(failure.unwrap_err().to_string().contains("null thread"));
    assert_eq!(calls, ["create"]);
    let (failure, calls) = execute(Trace {
        null_isolate: true,
        ..Trace::default()
    });
    assert!(failure.unwrap_err().to_string().contains("null isolate"));
    assert_eq!(calls, ["create", "cleanup"]);
}

#[test]
fn fingerprint_mismatch_unwinds_without_executing_entry() {
    for cleanup in [0, 23] {
        let (failure, calls) = execute(Trace {
            wrong_tag: true,
            cleanup_status: cleanup,
            ..Trace::default()
        });
        let failure = failure.unwrap_err();
        assert!(failure.to_string().contains("fingerprint mismatch"));
        assert_eq!(
            failure.cleanup,
            if cleanup == 0 { None } else { Some(cleanup) }
        );
        assert_eq!(calls, ["create", "tag0", "tag1", "tag2", "tag3", "cleanup"]);
    }
}

#[test]
fn cleanup_failure_retains_the_primary_benchmark_status() {
    for status in [0, 7, -1, 999] {
        let (failure, calls) = execute(Trace {
            entry_status: status,
            cleanup_status: 19,
            ..Trace::default()
        });
        let failure = failure.unwrap_err();
        assert_eq!(
            failure.exit_code,
            if status == 0 { 1 } else { exit_status(status) }
        );
        assert!(
            failure
                .to_string()
                .contains(&format!("benchmark returned status {status}"))
        );
        assert!(failure.to_string().contains("teardown also failed: 19"));
        assert_eq!(calls.last().unwrap(), "cleanup");
    }
}

#[test]
fn fingerprints_preserve_all_256_bits_including_signed_words() {
    let trace = "00000000000000017fffffffffffffff8000000000000000ffffffffffffffff";
    assert_eq!(expected_tags(trace).unwrap(), [1, i64::MAX, i64::MIN, -1]);
    assert_eq!(
        expected_tags(&trace.to_uppercase()).unwrap(),
        [1, i64::MAX, i64::MIN, -1]
    );
    for invalid in [
        "".into(),
        "0".repeat(63),
        "0".repeat(65),
        "g".repeat(64),
        "é".repeat(32),
    ] {
        assert!(expected_tags(&invalid).is_err(), "{invalid}");
    }
    let mut random = 0x534d485f414249_u64;
    for _ in 0..4096 {
        random ^= random << 13;
        random ^= random >> 7;
        random ^= random << 17;
        let text = format!("{random:016x}").repeat(4);
        assert_eq!(expected_tags(&text).unwrap(), [random as i64; 4]);
    }
}

#[test]
fn arguments_are_validated_before_loading_native_code() {
    assert!(arguments(&[]).unwrap_err().to_string().contains("usage"));
    assert!(arguments(&["one".into(), "two".into(), "three".into()]).is_err());
    assert!(arguments(&["library".into(), "bad\0path".into()]).is_err());
    let args = ["library".into()];
    let (path, config) = arguments(&args).unwrap();
    assert_eq!(path, Path::new("library"));
    assert_eq!(config.as_bytes(), b"");
    let args = ["library".into(), "résultats.json".into()];
    assert_eq!(
        arguments(&args).unwrap().1.as_bytes(),
        "résultats.json".as_bytes()
    );
    assert_eq!(run(vec!["--help".into()]).unwrap(), 0);
    assert_eq!(run(vec!["--version".into()]).unwrap(), 0);
}

#[cfg(not(miri))]
#[test]
fn dynamic_loader_failures_are_reported() {
    assert!(run(vec!["missing-library-does-not-exist".into()]).is_err());
    assert!(unsafe { load_library(Path::new("missing-library-does-not-exist")) }.is_err());
}

#[cfg(unix)]
#[test]
fn invalid_utf8_configuration_is_rejected_without_panicking() {
    use std::os::unix::ffi::OsStringExt;
    assert!(arguments(&["library".into(), OsString::from_vec(vec![0xff])]).is_err());
}
