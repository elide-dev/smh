// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
fn main() {
    if std::env::args().nth(1).as_deref() == Some("use-after-free") {
        let pointer = Box::into_raw(Box::new(17_u64));
        unsafe {
            drop(Box::from_raw(pointer));
            std::hint::black_box(std::ptr::read_volatile(pointer));
        }
    }
}
