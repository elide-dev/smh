// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class BenchmarkComparisonTest {
  @TempDir Path directory;

  static JsonObject row(double score) {
    JsonObject row =
        JsonParser.parseString(
                """
                {"benchmark":"app.Invoice.total","mode":"avgt","params":{"size":"10"},
                 "smh":{"backend":"wall"},
                 "primaryMetric":{"score":100,"scoreUnit":"us/op","rawData":[[90,100,110]]}}
                """)
            .getAsJsonObject();
    return rescore(row, score);
  }

  /** Sets the score and a three-sample distribution whose median equals it. */
  static JsonObject rescore(JsonObject row, double score) {
    JsonObject metric = row.getAsJsonObject("primaryMetric");
    metric.addProperty("score", score);
    JsonArray fork = new JsonArray();
    fork.add(score * .9);
    fork.add(score);
    fork.add(score * 1.1);
    JsonArray raw = new JsonArray();
    raw.add(fork);
    metric.add("rawData", raw);
    return row;
  }

  static JsonArray rows(JsonObject row) {
    JsonArray result = new JsonArray();
    result.add(row);
    return result;
  }

  @Test
  void deltasUseExactBenchmarkModeParameterAndUnitIdentity() throws Exception {
    Path baseline =
        Files.writeString(directory.resolve("baseline.json"), rows(row(100)).toString());
    BenchmarkComparison comparison = BenchmarkComparison.read(baseline);
    assertEquals(25, comparison.change(row(125)).orElseThrow(), 1e-10);
    assertEquals(-25, comparison.change(row(75)).orElseThrow(), 1e-10);
    assertEquals(0, comparison.change(row(100)).orElseThrow());
    assertEquals(-100, comparison.change(row(0)).orElseThrow());
    for (String field : new String[] {"benchmark", "mode"}) {
      var different = row(100);
      different.addProperty(field, "different");
      assertTrue(comparison.change(different).isEmpty());
    }
    var different = row(100);
    different.getAsJsonObject("params").addProperty("size", "20");
    assertTrue(comparison.change(different).isEmpty());
    different = row(100);
    different.getAsJsonObject("primaryMetric").addProperty("scoreUnit", "ns/op");
    assertTrue(comparison.change(different).isEmpty());
    assertTrue(new BenchmarkComparison(rows(row(0))).change(row(100)).isEmpty());
    assertTrue(
        new BenchmarkComparison(rows(row(Double.MIN_VALUE)))
            .change(row(Double.MAX_VALUE))
            .isEmpty());
    assertTrue(new BenchmarkComparison(rows(row(1e-300))).change(row(1e300)).isEmpty());
  }

  @Test
  void spreadBandsDescribeIterationDispersionWithoutClaimingSignificance() {
    var baseline = new BenchmarkComparison(rows(row(100)));
    var current = row(104);
    // Each run's MAD is 10% of its median, so the band is 20% and a 4% change sits inside it.
    assertEquals(20, baseline.spread(current).orElseThrow(), 1e-9);
    assertEquals(4, baseline.change(current).orElseThrow(), 1e-9);
    assertTrue(baseline.withinSpread(current));
    assertFalse(baseline.withinSpread(row(130)));
    assertEquals(100, baseline.previous(current).orElseThrow());
    assertTrue(baseline.previous(row(100).deepCopy()).isPresent());
    var unmatched = row(100);
    unmatched.addProperty("benchmark", "other");
    assertTrue(baseline.spread(unmatched).isEmpty());
    assertTrue(baseline.previous(unmatched).isEmpty());
    assertFalse(baseline.withinSpread(unmatched));
    var scoreOnly = row(120);
    scoreOnly.getAsJsonObject("primaryMetric").remove("rawData");
    assertEquals(20, baseline.change(scoreOnly).orElseThrow(), 1e-9);
    assertTrue(baseline.spread(scoreOnly).isEmpty());
    assertFalse(baseline.withinSpread(scoreOnly));
    var leyden = row(100);
    var execution = new JsonObject();
    execution.addProperty("harness", "jmh");
    execution.addProperty("backend", "hotspot");
    execution.addProperty("aotCacheKey", "abc");
    leyden.getAsJsonObject("smh").add("execution", execution);
    assertEquals("JMH · hotspot · Leyden", new BenchmarkComparison(rows(leyden)).description());
    var stats = BenchmarkComparison.stats(scoreOnly);
    assertEquals(0, stats.samples());
    assertFalse(stats.dispersed());
    assertTrue(Double.isNaN(stats.spread()));
    var constant = row(100);
    JsonArray fork = new JsonArray();
    fork.add(50);
    fork.add(50);
    JsonArray raw = new JsonArray();
    raw.add(fork);
    constant.getAsJsonObject("primaryMetric").add("rawData", raw);
    assertEquals(0, BenchmarkComparison.stats(constant).spread());
    assertEquals(-50, baseline.change(constant).orElseThrow(), 1e-9);
    assertEquals(10, baseline.spread(constant).orElseThrow(), 1e-9);
    assertFalse(baseline.withinSpread(constant));
    var zero = new BenchmarkComparison(rows(row(0)));
    assertTrue(zero.spread(row(5)).isEmpty());
    assertTrue(new BenchmarkComparison(rows(row(1))).spread(row(0)).isEmpty());
    assertFalse(BenchmarkComparison.higherIsBetter(row(1)));
    var throughput = row(1);
    throughput.addProperty("mode", "thrpt");
    assertTrue(BenchmarkComparison.higherIsBetter(throughput));
    var nonfinite = row(100);
    nonfinite
        .getAsJsonObject("primaryMetric")
        .getAsJsonArray("rawData")
        .get(0)
        .getAsJsonArray()
        .add(Double.NaN);
    assertEquals(3, BenchmarkComparison.stats(nonfinite).samples());
  }

  @Test
  void rejectsEmptyDuplicateInvalidAndMixedBackendBaselines() {
    assertThrows(IllegalArgumentException.class, () -> new BenchmarkComparison(new JsonArray()));
    JsonArray duplicate = rows(row(100));
    duplicate.add(row(100));
    assertThrows(IllegalArgumentException.class, () -> new BenchmarkComparison(duplicate));
    for (double score : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> new BenchmarkComparison(rows(row(score))));
    var simulation = row(100);
    simulation.getAsJsonObject("smh").addProperty("backend", "callgrind");
    assertThrows(IllegalArgumentException.class, () -> new BenchmarkComparison(rows(simulation)));
  }

  @Test
  void diagnosticAndTrainingRunsNeverAppearAsPerformanceDeltas() {
    var foreign = row(100);
    foreign.remove("smh");
    assertThrows(IllegalArgumentException.class, () -> new BenchmarkComparison(rows(foreign)));
    var measured = row(100);
    var identity = new com.google.gson.JsonObject();
    measured.getAsJsonObject("smh").add("execution", identity);
    var baseline = new BenchmarkComparison(rows(measured));
    identity.addProperty("purpose", "measurement");
    assertEquals(0, baseline.change(measured).orElseThrow());
    for (String purpose : java.util.List.of("diagnostic", "training")) {
      identity.addProperty("purpose", purpose);
      assertThrows(IllegalArgumentException.class, () -> new BenchmarkComparison(rows(measured)));
      assertThrows(IllegalArgumentException.class, () -> baseline.change(measured));
    }
  }
}
