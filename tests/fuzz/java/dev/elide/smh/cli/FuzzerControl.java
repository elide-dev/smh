// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

/** Deliberately broken target proving the fuzzer reports findings and saves reproducers. */
public final class FuzzerControl {
  public static void fuzzerTestOneInput(byte[] input) {
    if (input.length > 0) throw new AssertionError("SMH_FUZZER_NEGATIVE_CONTROL");
  }
}
