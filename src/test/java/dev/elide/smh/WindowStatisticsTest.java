// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh;

import static org.junit.jupiter.api.Assertions.*;

import dev.elide.smh.runtime.WindowStatistics;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("portable")
class WindowStatisticsTest {
  private WindowStatistics stats(Double... values) {
    return WindowStatistics.summarize(List.of(values), 0, values.length);
  }

  @Test
  void referenceTrace() {
    var result = stats(2., 4., 4., 4., 5., 5., 7., 9.);
    assertEquals(8, result.count());
    assertEquals(5., result.mean(), 1e-14);
    assertEquals(Math.sqrt(32. / 7), result.standardDeviation(), 1e-14);
    assertEquals(Math.sqrt(32. / 7) / 5, result.coefficientOfVariation(), 1e-14);
    assertEquals(2., result.minimum());
    assertEquals(9., result.maximum());
    var linear = stats(1., 2., 3., 4.);
    assertEquals(1.2, linear.relativeSlope(), 1e-14);
    assertEquals(Math.sqrt(5. / 3), linear.standardDeviation(), 1e-14);
    assertEquals(
        stats(1., 2., 3., 4.),
        WindowStatistics.summarize(List.of(999., 1., 2., 3., 4., 999.), 1, 5));
  }

  @Test
  void singletonAndZerosHaveDefinedDescriptiveStatistics() {
    var zero = stats(0., 0., 0.);
    assertEquals(new WindowStatistics(3, 0, 0, 0, 0, 0, 0), zero);
    assertEquals(0, zero.relativeDrift(stats(0.)));
    assertEquals(1, zero.relativeDrift(stats(4.)));
    assertEquals(1, stats(4.).relativeDrift(zero));
    assertEquals(.5, stats(2.).relativeDrift(stats(4.)));
    assertEquals(.5, stats(4.).relativeDrift(stats(2.)));
    assertEquals(new WindowStatistics(1, 4, 0, 0, 0, 4, 4), stats(4.));
  }

  @Test
  void invalidWindowsAndObservations() {
    assertThrows(IllegalArgumentException.class, () -> stats());
    assertThrows(
        IndexOutOfBoundsException.class, () -> WindowStatistics.summarize(List.of(1.), -1, 1));
    assertThrows(
        IndexOutOfBoundsException.class, () -> WindowStatistics.summarize(List.of(1.), 0, 2));
    for (double value :
        new double[] {-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> stats(1., value));
  }

  @Test
  void finiteExtremesDoNotOverflowSquares() {
    var maximum = stats(Double.MAX_VALUE, Double.MAX_VALUE);
    assertEquals(Double.MAX_VALUE, maximum.mean());
    assertEquals(0, maximum.standardDeviation());
    var ranged =
        stats(Double.MAX_VALUE / 4, Double.MAX_VALUE / 2, Double.MAX_VALUE * .75, Double.MAX_VALUE);
    assertTrue(Double.isFinite(ranged.standardDeviation()));
    assertEquals(Math.sqrt(5. / 3) / 2.5, ranged.coefficientOfVariation(), 1e-14);
    var tiny = stats(Double.MIN_VALUE, 2 * Double.MIN_VALUE);
    assertEquals(Math.sqrt(.5) / 1.5, tiny.coefficientOfVariation(), 1e-14);
    assertEquals(1, maximum.relativeDrift(stats(Double.MIN_VALUE)));
  }

  @Test
  void nearlyConstantLargeSignalsRetainTheirSmallVariance() {
    var result = stats(1e15, 1e15 + 1, 1e15 + 2, 1e15 + 3);
    assertEquals(1e15 + 1.5, result.mean());
    assertEquals(Math.sqrt(5. / 3), result.standardDeviation(), 1e-14);
    double predecessor = Math.nextDown(Double.MAX_VALUE);
    var extreme = stats(predecessor, Double.MAX_VALUE);
    double deviation = Math.ulp(Double.MAX_VALUE) / Math.sqrt(2);
    assertEquals(deviation, extreme.standardDeviation(), deviation * 1e-14);
  }

  @Test
  void independentReferenceAndScalePermutationProperties() {
    Random random = new Random(0x534d48);
    for (int trial = 0; trial < 2000; trial++) {
      List<Double> values = new ArrayList<>();
      BigDecimal sum = BigDecimal.ZERO;
      int count = 2 + random.nextInt(30);
      for (int i = 0; i < count; i++) {
        double value = 1 + random.nextInt(1000000);
        values.add(value);
        sum = sum.add(BigDecimal.valueOf(value));
      }
      double expectedMean =
          sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128).doubleValue();
      BigDecimal squared = BigDecimal.ZERO;
      for (double value : values) {
        BigDecimal delta = BigDecimal.valueOf(value).subtract(BigDecimal.valueOf(expectedMean));
        squared = squared.add(delta.multiply(delta));
      }
      double expectedDeviation =
          Math.sqrt(
              squared.divide(BigDecimal.valueOf(count - 1), MathContext.DECIMAL128).doubleValue());
      var actual = WindowStatistics.summarize(values, 0, count);
      assertEquals(
          expectedMean, actual.mean(), expectedMean * 1e-14, "seed=0x534d48 trial=" + trial);
      assertEquals(expectedDeviation, actual.standardDeviation(), expectedDeviation * 1e-13);
      double scale = Math.scalb(1., random.nextInt(60) - 30);
      List<Double> scaled = values.stream().map(v -> v * scale).toList();
      var transformed = WindowStatistics.summarize(scaled, 0, count);
      assertEquals(actual.coefficientOfVariation(), transformed.coefficientOfVariation(), 1e-14);
      assertEquals(actual.relativeSlope(), transformed.relativeSlope(), 1e-14);
      Collections.reverse(values);
      var reverse = WindowStatistics.summarize(values, 0, count);
      assertEquals(actual.mean(), reverse.mean(), expectedMean * 1e-14);
      assertEquals(-actual.relativeSlope(), reverse.relativeSlope(), 1e-13);
    }
  }
}
