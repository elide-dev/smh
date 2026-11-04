// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
use libloading::Library;
use std::{
    ffi::{CStr, CString, OsString},
    fmt,
    path::Path,
};

// Myna also emits named extern declarations. Dynamic loading uses its exact function-pointer
// types instead; only this generated module allows their C/Java names and unused declarations.
#[allow(dead_code, non_snake_case)]
mod abi {
    include!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/abi/generated/",
        env!("SMH_ABI_TARGET"),
        "/seam.rs"
    ));
}

#[derive(Debug)]
pub struct Failure {
    message: String,
    pub exit_code: u8,
    cleanup: Option<i32>,
}

impl Failure {
    fn new(message: impl fmt::Display) -> Self {
        Self {
            message: message.to_string(),
            exit_code: 1,
            cleanup: None,
        }
    }
}

impl fmt::Display for Failure {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}", self.message)?;
        if let Some(status) = self.cleanup {
            write!(formatter, "; isolate teardown also failed: {status}")?;
        }
        Ok(())
    }
}

impl std::error::Error for Failure {}

pub fn exit_status(status: i32) -> u8 {
    if status == 0 {
        0
    } else {
        status.clamp(1, 255) as u8
    }
}

fn expected_tags(fingerprint: &str) -> Result<[i64; 4], Failure> {
    let bytes = fingerprint.as_bytes();
    if bytes.len() != 64 || !bytes.iter().all(u8::is_ascii_hexdigit) {
        return Err(Failure::new("Invalid generated ABI fingerprint"));
    }
    let mut tags = [0_i64; 4];
    for (index, word) in bytes.chunks_exact(16).enumerate() {
        let bits = word.iter().fold(0_u64, |bits, byte| {
            let digit = if byte.is_ascii_digit() {
                byte - b'0'
            } else {
                byte.to_ascii_lowercase() - b'a' + 10
            };
            (bits << 4) | u64::from(digit)
        });
        tags[index] = bits as i64;
    }
    Ok(tags)
}

struct Api<'library> {
    create: abi::CreateIsolateFn,
    entry: abi::RunFn,
    tag: abi::AbiTagFn,
    tear_down: abi::TearDownFn,
    _library: std::marker::PhantomData<&'library Library>,
}

impl<'library> Api<'library> {
    unsafe fn load(library: &'library Library) -> Result<Self, Failure> {
        // All symbols resolve before an isolate is created. The caller keeps this trusted
        // Native Image library loaded until execution and teardown have both finished.
        unsafe {
            Ok(Self {
                create: *library
                    .get(b"graal_create_isolate\0")
                    .map_err(Failure::new)?,
                entry: *library.get(b"smh_run\0").map_err(Failure::new)?,
                tag: *library.get(b"smh_abi_tag\0").map_err(Failure::new)?,
                tear_down: *library
                    .get(b"graal_tear_down_isolate\0")
                    .map_err(Failure::new)?,
                _library: std::marker::PhantomData,
            })
        }
    }

    unsafe fn execute(&self, config: &CStr, expected: [i64; 4]) -> Result<i32, Failure> {
        let mut isolate = std::ptr::null_mut();
        let mut thread = std::ptr::null_mut();
        // These pointers address live Rust output slots. A zero create status must produce
        // nonnull handles. Once a thread exists, every later outcome attempts teardown once.
        let status = unsafe { (self.create)(std::ptr::null(), &mut isolate, &mut thread) };
        if status != 0 {
            return Err(Failure::new(format!("isolate creation failed: {status}")));
        }
        if thread.is_null() {
            return Err(Failure::new(
                "isolate creation returned a null thread; teardown is impossible",
            ));
        }
        let primary = if isolate.is_null() {
            Err(Failure::new("isolate creation returned a null isolate"))
        } else {
            // Fingerprint matching detects accidental ABI drift; it does not authenticate
            // hostile native code or make arbitrary pointers safe.
            let mut actual = [0_i64; 4];
            for (index, word) in actual.iter_mut().enumerate() {
                *word = unsafe { (self.tag)(thread, index as i32) };
            }
            if actual != expected {
                Err(Failure::new(format!(
                    "SMH ABI fingerprint mismatch: expected {expected:016x?}, got {actual:016x?}"
                )))
            } else {
                Ok(unsafe { (self.entry)(thread, config.as_ptr().cast()) })
            }
        };
        let cleanup = unsafe { (self.tear_down)(thread) };
        if cleanup == 0 {
            return primary;
        }
        let mut failure = match primary {
            Err(failure) => failure,
            Ok(status) => Failure {
                message: format!("benchmark returned status {status}"),
                exit_code: if status == 0 { 1 } else { exit_status(status) },
                cleanup: None,
            },
        };
        failure.cleanup = Some(cleanup);
        Err(failure)
    }
}

unsafe fn load_library(path: &Path) -> Result<Library, Failure> {
    // Libraries and their dependencies are trusted executable input, not a sandbox boundary.
    #[cfg(target_os = "windows")]
    unsafe {
        use libloading::os::windows::{
            LOAD_LIBRARY_SEARCH_DEFAULT_DIRS, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR,
            Library as WindowsLibrary,
        };
        WindowsLibrary::load_with_flags(
            path,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS,
        )
        .map(Library::from)
        .map_err(Failure::new)
    }
    #[cfg(not(target_os = "windows"))]
    unsafe {
        Library::new(path).map_err(Failure::new)
    }
}

fn arguments(args: &[OsString]) -> Result<(&Path, CString), Failure> {
    if args.is_empty() {
        return Err(Failure::new(
            "usage: smh-bootstrap LIBRARY [RUN_CONFIG.json]",
        ));
    }
    if args.len() > 2 {
        return Err(Failure::new("too many arguments"));
    }
    let config = match args.get(1) {
        Some(config) => config
            .to_str()
            .ok_or_else(|| Failure::new("Run configuration path must be UTF-8"))?,
        None => "",
    };
    Ok((
        Path::new(&args[0]),
        CString::new(config).map_err(Failure::new)?,
    ))
}

pub fn run(args: Vec<OsString>) -> Result<i32, Failure> {
    if args == ["--help"] {
        println!("usage: smh-bootstrap LIBRARY [RUN_CONFIG.json]");
        return Ok(0);
    }
    if args == ["--version"] {
        println!(
            "smh-bootstrap {} (ABI {})",
            env!("CARGO_PKG_VERSION"),
            env!("SMH_ABI_TARGET")
        );
        return Ok(0);
    }
    let (path, config) = arguments(&args)?;
    let expected = expected_tags(abi::ABI_FINGERPRINT)?;
    let path = path.canonicalize().map_err(Failure::new)?;
    unsafe {
        let library = load_library(&path)?;
        let api = Api::load(&library)?;
        api.execute(&config, expected)
    }
}

#[cfg(test)]
#[path = "../tests/unit/bootstrap.rs"]
mod tests;
