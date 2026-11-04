// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

/** Wall durations and simulator events are different measurement quantities. */
public enum MeasurementBackend {
  WALL,
  CALLGRIND;

  public static MeasurementBackend parse(String name) {
    return switch (name) {
      case "wall" -> WALL;
      case "callgrind" -> CALLGRIND;
      default -> throw new IllegalArgumentException("Unsupported measurement backend: " + name);
    };
  }
}
