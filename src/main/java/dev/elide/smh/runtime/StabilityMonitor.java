// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded descriptive convergence; independent of compiler correctness and JIT activity. */
public final class StabilityMonitor {
  public enum Status {
    COLLECTING,
    STABLE,
    LIMIT_REACHED
  }

  public enum Reason {
    MINIMUM_SAMPLES,
    DISPERSION,
    DRIFT,
    TREND,
    CONSECUTIVE_WINDOWS,
    CONVERGED,
    SAMPLE_LIMIT
  }

  public record Result(
      Status status,
      Reason reason,
      int samples,
      int stableWindows,
      WindowStatistics window,
      double relativeDrift) {}

  private final StabilityPolicy policy;
  private final List<Double> history = new ArrayList<>();
  private int stableWindows;
  private Result result = new Result(Status.COLLECTING, Reason.MINIMUM_SAMPLES, 0, 0, null, 0);

  public StabilityMonitor(StabilityPolicy policy) {
    this.policy = Objects.requireNonNull(policy);
  }

  public Result accept(double observation) {
    if (result.status() != Status.COLLECTING)
      throw new IllegalStateException("The phase has already stopped");
    if (!Double.isFinite(observation) || observation < 0)
      throw new IllegalArgumentException("Observations must be finite and nonnegative");
    history.add(observation);
    int count = history.size();
    if (count < policy.minimumSamples()) {
      result = new Result(Status.COLLECTING, Reason.MINIMUM_SAMPLES, count, 0, null, 0);
      return result;
    }
    WindowStatistics current =
        WindowStatistics.summarize(history, count - policy.windowSize(), count);
    WindowStatistics previous =
        WindowStatistics.summarize(
            history, count - 2 * policy.windowSize(), count - policy.windowSize());
    double drift = current.relativeDrift(previous);
    Reason reason;
    if (current.coefficientOfVariation() > policy.maximumRelativeDeviation())
      reason = Reason.DISPERSION;
    else if (drift > policy.maximumRelativeDrift()) reason = Reason.DRIFT;
    else if (Math.abs(current.relativeSlope()) > policy.maximumRelativeSlope())
      reason = Reason.TREND;
    else reason = Reason.CONSECUTIVE_WINDOWS;
    stableWindows = reason == Reason.CONSECUTIVE_WINDOWS ? stableWindows + 1 : 0;
    if (stableWindows >= policy.consecutiveWindows())
      result = new Result(Status.STABLE, Reason.CONVERGED, count, stableWindows, current, drift);
    else if (count == policy.maximumSamples())
      result =
          new Result(
              Status.LIMIT_REACHED, Reason.SAMPLE_LIMIT, count, stableWindows, current, drift);
    else result = new Result(Status.COLLECTING, reason, count, stableWindows, current, drift);
    return result;
  }

  public Result result() {
    return result;
  }

  public List<Double> history() {
    return List.copyOf(history);
  }
}
