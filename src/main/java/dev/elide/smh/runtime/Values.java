// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

/** Strict parameter conversions used by generated code. */
public final class Values {
  private Values() {}

  public static boolean bool(String value) {
    if (!value.equals("true") && !value.equals("false"))
      throw new IllegalArgumentException("Invalid boolean: " + value);
    return Boolean.parseBoolean(value);
  }

  public static char character(String value) {
    if (value.length() != 1) throw new IllegalArgumentException("Expected one character: " + value);
    return value.charAt(0);
  }
}
