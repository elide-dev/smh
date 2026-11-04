// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

/** Predeclared work bounds for selecting a fixed wall batch before warmup. */
public record BatchCalibrationPolicy(
    long targetNanos, int maximumBatchSize, int maximumAttempts, int samplesPerAttempt) {
  public BatchCalibrationPolicy {
    if (targetNanos < 1
        || maximumBatchSize < 1
        || maximumAttempts < 1
        || maximumAttempts > 32
        || samplesPerAttempt < 1
        || samplesPerAttempt > 64)
      throw new IllegalArgumentException(
          "Calibration needs positive target/batch bounds, 1..32 attempts and 1..64 samples");
  }
}
