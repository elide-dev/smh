// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import java.util.List;
import java.util.Objects;

/** Descriptive statistics for nonnegative observations; no independence or accuracy claim. */
public record WindowStatistics(
    int count,
    double mean,
    double standardDeviation,
    double coefficientOfVariation,
    double relativeSlope,
    double minimum,
    double maximum) {

  /** Center before scaling: retain small variance without overflowing large finite squares. */
  public static WindowStatistics summarize(List<Double> observations, int from, int to) {
    Objects.checkFromToIndex(from, to, observations.size());
    if (from == to) throw new IllegalArgumentException("An observation window cannot be empty");
    double minimum = Double.POSITIVE_INFINITY;
    double maximum = 0;
    for (int i = from; i < to; i++) {
      double value = observations.get(i);
      if (!Double.isFinite(value) || value < 0)
        throw new IllegalArgumentException("Observations must be finite and nonnegative");
      minimum = Math.min(minimum, value);
      maximum = Math.max(maximum, value);
    }
    int count = to - from;
    double range = maximum - minimum;
    if (range == 0) return new WindowStatistics(count, minimum, 0, 0, 0, minimum, maximum);
    double sum = 0;
    double correction = 0;
    for (int i = from; i < to; i++) {
      double adjusted = (observations.get(i) - minimum) / range - correction;
      double updated = sum + adjusted;
      correction = (updated - sum) - adjusted;
      sum = updated;
    }
    double scaledMean = sum / count;
    double squaredResiduals = 0;
    double covariance = 0;
    double indexVariance = 0;
    double midpoint = (count - 1) / 2.0;
    for (int i = 0; i < count; i++) {
      double residual = (observations.get(from + i) - minimum) / range - scaledMean;
      squaredResiduals += residual * residual;
      double centeredIndex = i - midpoint;
      covariance += centeredIndex * residual;
      indexVariance += centeredIndex * centeredIndex;
    }
    // A nonconstant window necessarily has at least two observations.
    double scaledDeviation = Math.sqrt(squaredResiduals / (count - 1));
    double scaledAbsoluteMean = minimum / range + scaledMean;
    double relativeSlope = covariance / indexVariance * (count - 1) / scaledAbsoluteMean;
    return new WindowStatistics(
        count,
        minimum + range * scaledMean,
        range * scaledDeviation,
        scaledDeviation / scaledAbsoluteMean,
        relativeSlope,
        minimum,
        maximum);
  }

  /** Symmetric mean drift in [0,1], scaled to avoid overflow/underflow in the denominator. */
  public double relativeDrift(WindowStatistics previous) {
    double scale = Math.max(mean, previous.mean);
    if (scale == 0) return 0;
    return Math.abs(mean / scale - previous.mean / scale);
  }
}
