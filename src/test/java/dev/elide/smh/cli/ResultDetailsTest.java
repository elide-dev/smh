// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("portable")
class ResultDetailsTest {
  @Test
  void headlineNumbersRoundWithoutPaddingAndKeepTheUnits() {
    assertEquals("0", ResultDetails.number(0));
    assertEquals("0", ResultDetails.number(-0.0));
    assertEquals("1e−5", ResultDetails.number(.00001));
    assertEquals("1.23e−6", ResultDetails.number(.0000012345));
    assertEquals("1e+9", ResultDetails.number(1e9));
    assertEquals("1.25", ResultDetails.number(1.25));
    assertEquals("16.3", ResultDetails.number(16.280));
    assertEquals("158", ResultDetails.number(158.4));
    assertEquals("1000", ResultDetails.number(999.9));
    assertEquals("1e+6", ResultDetails.number(999_999));
    assertEquals("-123", ResultDetails.number(-123.45));
    assertEquals("1.8e+308", ResultDetails.number(Double.MAX_VALUE));
    assertEquals("4.9e−324", ResultDetails.number(Double.MIN_VALUE));
    for (double value :
        new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
      assertEquals("—", ResultDetails.number(value));
    assertEquals("µs/op", ResultDetails.unit("us/op"));
    assertEquals("ops/µs", ResultDetails.unit("ops/us"));
    assertEquals("12", ResultDetails.compactCount(12));
    assertEquals("1.25k", ResultDetails.compactCount(1250));
    assertEquals("1.25m", ResultDetails.compactCount(1_250_000));
    assertEquals(1, ResultDetails.quantile(List.of(1.0, 2.0), 0));
    assertEquals(2, ResultDetails.quantile(List.of(1.0, 2.0), .99));
  }

  @Test
  void equalWidthBinsCountEveryObservationIncludingBothEndpointsAndLongTails() {
    assertArrayEquals(
        new int[] {2, 1, 1, 2}, ResultDetails.histogram(List.of(0.0, .99, 1.0, 2.0, 3.0, 4.0), 4));
    assertArrayEquals(new int[] {0, 0, 3, 0}, ResultDetails.histogram(List.of(2.0, 2.0, 2.0), 4));
    assertArrayEquals(
        new int[] {1, 0, 1, 1},
        ResultDetails.histogram(List.of(-Double.MAX_VALUE, 0.0, Double.MAX_VALUE), 4));
    assertArrayEquals(
        new int[] {1, 1},
        ResultDetails.histogram(List.of(Double.MIN_VALUE, 2 * Double.MIN_VALUE), 2));
    assertArrayEquals(new int[] {2}, ResultDetails.histogram(List.of(0.0, 1.0), 1));
    var samples = new ArrayList<>(Collections.nCopies(1000, 1.0));
    samples.add(100.0);
    for (int bins : List.of(2, 7, 19, 61)) {
      int[] histogram = ResultDetails.histogram(samples, bins);
      assertEquals(1001, Arrays.stream(histogram).sum());
      assertEquals(1000, histogram[0]);
      assertEquals(1, histogram[bins - 1]);
    }
    String result = plain(ResultDetails.render(row(), samples, 80));
    assertTrue(result.lines().anyMatch(line -> line.endsWith("▁▁")), result);
    assertTrue(result.contains("1000 │"), result);
    assertTrue(result.contains("1k iterations"), result);
  }

  @Test
  void histogramShowsARealShapeAndIterationScoreSemantics() {
    List<Double> samples = List.of(15.0, 16.0, 16.0, 16.0, 16.0, 17.0, 17.0, 19.0);
    String colored = ResultDetails.render(row(), samples);
    String result = plain(colored);
    assertTrue(colored.contains(TerminalDisplay.CYAN));
    assertTrue(result.contains("Iterations"));
    assertTrue(result.contains("Iteration score · µs/op"));
    assertTrue(result.contains("4 │"));
    assertTrue(result.contains("0 └"));
    assertTrue(result.contains("median 16"));
    assertTrue(result.contains("p99 19"));
    assertTrue(result.contains("8 iterations"));
    assertTrue(result.contains("██"));
    assertEquals(2, result.lines().toList().get(1).chars().filter(c -> c == '█').count());
    assertEquals(8, result.lines().toList().get(4).chars().filter(c -> c == '█').count());
    assertFalse(result.contains("Run order"));
    assertFalse(result.contains("CV"));
    assertFalse(result.contains("reference"));
    for (int width : List.of(40, 58, 80, 109, 110, 140, 160)) {
      String rendered = plain(ResultDetails.render(row(), samples, width));
      assertEquals(width >= 110, rendered.contains("Run order"));
      for (String line : rendered.lines().toList()) assertTrue(line.length() <= width, line);
    }
  }

  @Test
  void runOrderRetainsIsolatedSpikesInsideEveryAggregatedColumn() {
    for (int spike : List.of(0, 1, 17, 499, 501, 998, 999)) {
      var samples = new ArrayList<>(Collections.nCopies(1000, 1.0));
      samples.set(spike, 100.0);
      String colored = ResultDetails.render(row(), samples, 140);
      String result = plain(colored);
      assertTrue(colored.contains(TerminalDisplay.VIOLET));
      var lines = result.lines().toList();
      int column = lines.getFirst().indexOf("Run order");
      assertTrue(column > 0);
      assertTrue(lines.getFirst().contains("min–max per column"));
      assertTrue(result.contains("Iteration index"));
      assertTrue(lines.get(1).substring(column).contains("┃"), result);
      assertTrue(lines.get(4).substring(column).contains("●"), result);
      assertTrue(result.contains("1000"));
    }
  }

  @Test
  void finiteBoundariesAndConstantSeriesRemainVisible() {
    for (List<Double> samples :
        List.of(
            List.of(0.0),
            List.of(3.0, 3.0),
            List.of(-Double.MAX_VALUE, Double.MAX_VALUE),
            List.of(Double.MIN_VALUE, 2 * Double.MIN_VALUE),
            List.of(1.0, Math.nextUp(1.0)),
            List.of(1.234567890123456, Math.nextUp(1.234567890123456)))) {
      for (int width : List.of(40, 110, 160)) {
        String result = plain(ResultDetails.render(row(), samples, width));
        assertTrue(result.contains("██"), result);
        assertFalse(result.contains("NaN"), result);
        assertFalse(result.contains("Infinity"), result);
        assertTrue(result.contains("p99"), result);
        for (String line : result.lines().toList()) assertTrue(line.length() <= width, line);
      }
    }
    String adjacent = plain(ResultDetails.render(row(), List.of(1.0, Math.nextUp(1.0)), 40));
    assertTrue(adjacent.contains("1.0000000000000002"));
    String midpoint =
        plain(ResultDetails.render(row(), List.of(-Double.MAX_VALUE, Double.MAX_VALUE)));
    assertTrue(midpoint.contains("median 0"));
    String tinyMedian =
        plain(ResultDetails.render(row(), List.of(Double.MIN_VALUE, 2 * Double.MIN_VALUE)));
    assertTrue(tinyMedian.contains("median 9.9e−324"));
    String hugeMedian =
        plain(ResultDetails.render(row(), List.of(Double.MAX_VALUE, Double.MAX_VALUE)));
    assertTrue(hugeMedian.contains("median 1.8e+308"));
    assertTrue(plain(ResultDetails.render(new JsonObject(), List.of(2.0))).contains("1 iteration"));
    assertTrue(
        plain(ResultDetails.render(row(), List.of(16.3, 18.9), 10)).contains("Iteration score"));
  }

  @Test
  void omittedNonfiniteObservationsAreExplicitAndNeverInventAPopulation() {
    String result =
        plain(ResultDetails.render(row(), List.of(1.0, Double.NaN, Double.POSITIVE_INFINITY)));
    assertTrue(result.contains("1 iteration"));
    assertTrue(result.contains("2 nonfinite scores omitted"));
    assertFalse(result.contains("NaN"));
    assertTrue(
        plain(ResultDetails.render(row(), List.of(1.0, Double.NaN), 140))
            .contains("Finite-score index"));
    for (List<Double> samples : List.of(List.<Double>of(), List.of(Double.NaN))) {
      String empty = plain(ResultDetails.render(row(), samples));
      assertTrue(empty.contains("No finite iteration scores"));
      assertFalse(empty.contains("median"));
    }
  }

  @Test
  void phaseLimitsAndFailureReasonsStayVisibleWithoutClaimingStability() {
    JsonObject row = row();
    row.add(
        "smh",
        JsonParser.parseString(
            """
            {"warmup":{"status":"unstable","stoppingReason":"budget-exhausted"},
             "measurement":{"status":"LIMIT_REACHED","stoppingReason":"SAMPLE_LIMIT"}}
            """));
    String result = plain(ResultDetails.render(row, List.of(1.0, 3.0)));
    assertTrue(result.contains("warmup · budget exhausted"));
    assertTrue(result.contains("measurement · sample limit"));
    assertTrue(result.contains("median 2"));
    assertFalse(result.contains("stable"));
    assertTrue(ResultDetails.conditions(row).contains(TerminalDisplay.GOLD));
    for (String phase :
        List.of(
            "{}",
            "{\"status\":\"unknown\"}",
            "{\"status\":\"STABLE\",\"stoppingReason\":\"CONVERGED\"}",
            "{\"status\":\"not-assessed\",\"stoppingReason\":\"fixed-count\"}")) {
      row.add("smh", JsonParser.parseString("{\"measurement\":" + phase + "}"));
      assertEquals("", ResultDetails.conditions(row));
    }
    row.add("smh", JsonParser.parseString("{\"measurement\":{\"status\":\"failed\"}}"));
    assertTrue(plain(ResultDetails.conditions(row)).contains("measurement · failed"));
    row.add(
        "smh",
        JsonParser.parseString(
            "{\"measurement\":{\"status\":\"failed\",\"stoppingReason\":\"fixed-count\"}}"));
    assertTrue(plain(ResultDetails.conditions(row)).contains("measurement · failed"));
    row.add(
        "smh", JsonParser.parseString("{\"measurement\":{\"stoppingReason\":\"interrupted\"}}"));
    assertTrue(plain(ResultDetails.conditions(row)).contains("measurement · interrupted"));
    row.add("smh", new JsonObject());
    assertEquals("", ResultDetails.conditions(row));
    row.remove("smh");
    assertEquals("", ResultDetails.conditions(row));
  }

  private static JsonObject row() {
    return JsonParser.parseString("{\"primaryMetric\":{\"scoreUnit\":\"us/op\"}}")
        .getAsJsonObject();
  }

  private static String plain(String value) {
    return value.replaceAll("\033\\[[0-9;]*m", "");
  }
}
