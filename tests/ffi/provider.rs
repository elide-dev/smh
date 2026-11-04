// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
//! Deliberately controllable native provider for loader/lifecycle negative tests; not an SVM image.
use std::{ffi::c_void, io::Write};

#[allow(dead_code, non_snake_case)]
mod abi {
    include!(env!("SMH_TEST_ABI_RS"));
}

fn event(name: &str) {
    if let Some(path) = std::env::var_os("SMH_TEST_EVENT_LOG") {
        if let Ok(mut file) = std::fs::OpenOptions::new()
            .append(true)
            .create(true)
            .open(path)
        {
            let _ = writeln!(file, "{name}");
        }
    }
}

fn status(name: &str) -> i32 {
    std::env::var(name)
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(0)
}

#[cfg(not(omit_create))]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn graal_create_isolate(
    _: *const c_void,
    isolate: *mut *mut c_void,
    thread: *mut *mut c_void,
) -> i32 {
    event("create");
    unsafe {
        *isolate = std::ptr::dangling_mut::<u8>().cast();
        *thread = std::ptr::dangling_mut::<u8>().cast();
    }
    status("SMH_TEST_CREATE_STATUS")
}
#[cfg(not(omit_create))]
const _: abi::CreateIsolateFn = graal_create_isolate;

#[cfg(not(omit_entry))]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn smh_run(_: *mut c_void, _: *const u8) -> i32 {
    #[cfg(asan_overflow)]
    {
        // Deliberate instrumentor control; never part of a clean provider variant.
        let allocation = Box::new([0_u8; 8]);
        let offset = std::hint::black_box(8);
        unsafe {
            std::hint::black_box(std::ptr::read_volatile(allocation.as_ptr().add(offset)));
        }
    }
    #[cfg(asan_leak)]
    {
        // The pointer is deliberately lost; the bootstrap must detect this at process exit.
        std::hint::black_box(Box::into_raw(Box::new([0_u8; 123])));
    }
    #[cfg(asan_use_after_free)]
    {
        let pointer = Box::into_raw(Box::new(17_u64));
        unsafe {
            drop(Box::from_raw(pointer));
            std::hint::black_box(std::ptr::read_volatile(std::hint::black_box(pointer)));
        }
    }
    event("entry");
    status("SMH_TEST_ENTRY_STATUS")
}
#[cfg(not(omit_entry))]
const _: abi::RunFn = smh_run;

#[cfg(not(omit_tag))]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn smh_abi_tag(_: *mut c_void, index: i32) -> i64 {
    event(&format!("tag{index}"));
    if cfg!(wrong_tag) {
        return 0;
    }
    let fingerprint = abi::ABI_FINGERPRINT;
    let offset = index as usize * 16;
    u64::from_str_radix(&fingerprint[offset..offset + 16], 16).unwrap() as i64
}
#[cfg(not(omit_tag))]
const _: abi::AbiTagFn = smh_abi_tag;

#[cfg(not(omit_cleanup))]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn graal_tear_down_isolate(_: *mut c_void) -> i32 {
    event("cleanup");
    status("SMH_TEST_CLEANUP_STATUS")
}
#[cfg(not(omit_cleanup))]
const _: abi::TearDownFn = graal_tear_down_isolate;
