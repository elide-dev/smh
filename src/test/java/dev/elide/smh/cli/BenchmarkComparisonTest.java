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
    row.getAsJsonObject("primaryMetric").addProperty("score", score);
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
}
