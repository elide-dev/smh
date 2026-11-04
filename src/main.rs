// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
//! Native entrypoint for an AOT benchmark library with a generated, fingerprinted ABI.
mod bootstrap;

fn main() -> std::process::ExitCode {
    match bootstrap::run(std::env::args_os().skip(1).collect()) {
        Ok(status) => std::process::ExitCode::from(bootstrap::exit_status(status)),
        Err(failure) => {
            eprintln!("smh-bootstrap: {failure}");
            std::process::ExitCode::from(failure.exit_code)
        }
    }
}
