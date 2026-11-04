// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

/** Predeclared bounds and descriptive convergence tolerances for one run phase. */
public record StabilityPolicy(
    int minimumSamples,
    int maximumSamples,
    int windowSize,
    int consecutiveWindows,
    double maximumRelativeDeviation,
    double maximumRelativeDrift,
    double maximumRelativeSlope) {
  public StabilityPolicy {
    if (windowSize < 2) throw new IllegalArgumentException("windowSize must be at least two");
    if (minimumSamples < 2L * windowSize)
      throw new IllegalArgumentException("minimumSamples must include two complete windows");
    if (consecutiveWindows < 1)
      throw new IllegalArgumentException("consecutiveWindows must be positive");
    if (maximumSamples < (long) minimumSamples + consecutiveWindows - 1)
      throw new IllegalArgumentException(
          "maximumSamples cannot satisfy the minimum stable-window count");
    checkTolerance(maximumRelativeDeviation);
    checkTolerance(maximumRelativeDrift);
    checkTolerance(maximumRelativeSlope);
  }

  private static void checkTolerance(double value) {
    if (!Double.isFinite(value) || value < 0)
      throw new IllegalArgumentException("Stability tolerances must be finite and nonnegative");
  }
}
