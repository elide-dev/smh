// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh;

import static org.junit.jupiter.api.Assertions.*;

import dev.elide.smh.runtime.*;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("portable")
class StabilityMonitorTest {
  private StabilityPolicy policy(
      int consecutive, int maximum, double cv, double drift, double slope) {
    return new StabilityPolicy(4, maximum, 2, consecutive, cv, drift, slope);
  }

  @Test
  void declaredMinimumAndConsecutiveWindows() {
    var monitor = new StabilityMonitor(policy(2, 10, 0, 0, 0));
    assertEquals(StabilityMonitor.Status.COLLECTING, monitor.result().status());
    assertTrue(monitor.history().isEmpty());
    for (int i = 0; i < 3; i++)
      assertEquals(StabilityMonitor.Reason.MINIMUM_SAMPLES, monitor.accept(7).reason());
    var first = monitor.accept(7);
    assertEquals(StabilityMonitor.Status.COLLECTING, first.status());
    assertEquals(StabilityMonitor.Reason.CONSECUTIVE_WINDOWS, first.reason());
    assertEquals(1, first.stableWindows());
    assertEquals(4, first.samples());
    assertEquals(7, first.window().mean());
    assertEquals(0, first.relativeDrift());
    var result = monitor.accept(7);
    assertEquals(StabilityMonitor.Status.STABLE, result.status());
    assertEquals(StabilityMonitor.Reason.CONVERGED, result.reason());
    assertEquals(2, result.stableWindows());
    assertEquals(List.of(7., 7., 7., 7., 7.), monitor.history());
    assertThrows(UnsupportedOperationException.class, () -> monitor.history().add(9.));
    assertThrows(IllegalStateException.class, () -> monitor.accept(7));
  }

  @Test
  void unstableRunsStopAtBudget() {
    var monitor = new StabilityMonitor(policy(1, 6, 0, 0, 0));
    for (int i = 0; i < 4; i++) monitor.accept(1 + i % 2);
    assertEquals(StabilityMonitor.Reason.DISPERSION, monitor.result().reason());
    assertEquals(StabilityMonitor.Status.COLLECTING, monitor.result().status());
    monitor.accept(1);
    var limit = monitor.accept(2);
    assertEquals(StabilityMonitor.Status.LIMIT_REACHED, limit.status());
    assertEquals(StabilityMonitor.Reason.SAMPLE_LIMIT, limit.reason());
    assertEquals(6, monitor.history().size());
    assertThrows(IllegalStateException.class, () -> monitor.accept(1));
  }

  @Test
  void meanDriftAndTrendAreSeparateRejectionReasons() {
    var drift = new StabilityMonitor(policy(1, 10, 100, 0, 100));
    for (double value : new double[] {10, 10, 20, 20}) drift.accept(value);
    assertEquals(StabilityMonitor.Reason.DRIFT, drift.result().reason());
    assertEquals(.5, drift.result().relativeDrift());
    var trend = new StabilityMonitor(policy(1, 10, 100, 100, 0));
    for (double value : new double[] {1, 2, 3, 4}) trend.accept(value);
    assertEquals(StabilityMonitor.Reason.TREND, trend.result().reason());
  }

  @Test
  void failedWindowResetsStreakAndCanRecover() {
    var monitor = new StabilityMonitor(policy(3, 20, 0, 0, 0));
    for (int i = 0; i < 5; i++) monitor.accept(1);
    assertEquals(2, monitor.result().stableWindows());
    monitor.accept(2);
    assertEquals(0, monitor.result().stableWindows());
    assertEquals(StabilityMonitor.Reason.DISPERSION, monitor.result().reason());
    var snapshot = monitor.history();
    while (monitor.result().status() == StabilityMonitor.Status.COLLECTING) monitor.accept(2);
    assertEquals(StabilityMonitor.Status.STABLE, monitor.result().status());
    assertEquals(6, snapshot.size());
  }

  @Test
  void convergenceAtLimitIsValidAndDoesNotCertifyUsefulWork() {
    var monitor = new StabilityMonitor(policy(1, 4, 0, 0, 0));
    for (int i = 0; i < 4; i++) monitor.accept(0);
    assertEquals(StabilityMonitor.Status.STABLE, monitor.result().status());
    assertEquals(0, monitor.result().window().mean());
  }

  @Test
  void invalidObservationsDoNotEnterHistory() {
    assertThrows(NullPointerException.class, () -> new StabilityMonitor(null));
    var monitor = new StabilityMonitor(policy(1, 4, 0, 0, 0));
    for (double invalid :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1})
      assertThrows(IllegalArgumentException.class, () -> monitor.accept(invalid));
    assertEquals(0, monitor.history().size());
    assertEquals(0, monitor.result().samples());
  }

  @Test
  void invalidPoliciesAndIntegerOverflowAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new StabilityPolicy(4, 10, 1, 1, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new StabilityPolicy(3, 10, 2, 1, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StabilityPolicy(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> policy(0, 10, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> policy(3, 5, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StabilityPolicy(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 2, Integer.MAX_VALUE, 0, 0, 0));
    for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}) {
      assertThrows(IllegalArgumentException.class, () -> policy(1, 10, invalid, 0, 0));
      assertThrows(IllegalArgumentException.class, () -> policy(1, 10, 0, invalid, 0));
      assertThrows(IllegalArgumentException.class, () -> policy(1, 10, 0, 0, invalid));
    }
  }
}
