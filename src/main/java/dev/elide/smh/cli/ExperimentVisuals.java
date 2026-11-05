// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static dev.elide.smh.cli.TerminalDisplay.*;

import com.google.gson.*;
import java.math.*;
import java.util.*;

/**
 * Mode-specific views: fresh launches, sustained histories, and explicit measurement boundaries.
 */
final class ExperimentVisuals {
  private ExperimentVisuals() {}

  static String render(JsonObject row, List<Double> samples, int width) {
    JsonObject smh = row.getAsJsonObject("smh");
    boolean startup = smh.get("experimentMode").getAsString().equals("startup");
    String units =
        ResultDetails.unit(row.getAsJsonObject("primaryMetric").get("scoreUnit").getAsString());
    List<Double> positions = new ArrayList<>();
    if (!startup)
      for (JsonElement sample : smh.getAsJsonObject("measurement").getAsJsonArray("observations"))
        positions.add(sample.getAsJsonObject().get("endNanos").getAsDouble() / 1e9);
    StringBuilder result =
        new StringBuilder(
            panels(
                samples,
                positions,
                startup,
                units,
                startup ? "Launch order" : "Elapsed seconds",
                width));
    if (startup) {
      boolean simulated = smh.get("backend").getAsString().equals("callgrind");
      BigDecimal endpoint = BigDecimal.ZERO, after = BigDecimal.ZERO;
      JsonArray observations = smh.getAsJsonArray("observations");
      for (JsonElement sample : observations) {
        JsonObject observation = sample.getAsJsonObject();
        endpoint = endpoint.add(observation.get("score").getAsBigDecimal());
        if (simulated) {
          JsonObject events = observation.getAsJsonObject("postEndpointEvents");
          if (events.has("Ir")) after = after.add(events.get("Ir").getAsBigDecimal());
        } else
          after =
              after.add(
                  observation
                      .get("completionNanos")
                      .getAsBigDecimal()
                      .subtract(observation.get("endpointNanos").getAsBigDecimal())
                      .max(BigDecimal.ZERO)
                      .movePointLeft(6));
      }
      BigDecimal count = BigDecimal.valueOf(observations.size());
      endpoint = endpoint.divide(count, MathContext.DECIMAL128);
      after = after.divide(count, MathContext.DECIMAL128);
      BigDecimal max = endpoint.max(after);
      result
          .append("\n    ")
          .append(DIM)
          .append(simulated ? "Instructions per launch" : "ms per launch")
          .append(RESET)
          .append('\n');
      int cells = Math.clamp(width - 36, 8, 48);
      String label =
          switch (smh.get("endpoint").getAsString()) {
            case "ready" -> "Ready";
            case "exit" -> "Full run";
            default -> "First result";
          };
      SimulationDisplay.bar(
          result, String.format(Locale.ROOT, "%-14s", label), endpoint, max, cells, CYAN);
      SimulationDisplay.bar(
          result, String.format(Locale.ROOT, "%-14s", "Rest of run"), after, max, cells, GOLD);
      result
          .append("    ")
          .append(DIM)
          .append("Rest of run excluded from score")
          .append(RESET)
          .append('\n');
    }
    return result.append(ResultDetails.conditions(row)).append('\n').toString();
  }

  static String simulationSoak(JsonObject row, int width) {
    List<Double> scores = new ArrayList<>(), positions = new ArrayList<>();
    BigDecimal completed = BigDecimal.ZERO;
    for (JsonElement sample : row.getAsJsonArray("observations")) {
      JsonObject observation = sample.getAsJsonObject();
      BigDecimal operations = observation.get("operations").getAsBigDecimal();
      scores.add(
          observation
              .getAsJsonObject("events")
              .get("Ir")
              .getAsBigDecimal()
              .divide(operations, MathContext.DECIMAL128)
              .doubleValue());
      completed = completed.add(operations);
      positions.add(completed.doubleValue());
    }
    String result =
        "\n" + panels(scores, positions, false, "instructions/op", "Completed operations", width);
    BigDecimal last =
        row.getAsJsonArray("observations")
            .asList()
            .getLast()
            .getAsJsonObject()
            .get("operations")
            .getAsBigDecimal();
    if (last.compareTo(row.get("operationsPerIteration").getAsBigDecimal()) < 0)
      result +=
          "    "
              + GOLD
              + "Partial window · "
              + SimulationDisplay.number(last)
              + " operations"
              + RESET
              + "\n";
    return result;
  }

  private static String panels(
      List<Double> scores,
      List<Double> positions,
      boolean startup,
      String units,
      String axis,
      int width) {
    if (scores.isEmpty() || scores.stream().anyMatch(v -> !Double.isFinite(v)))
      return "    " + GOLD + "Incomplete experiment observations; see raw data" + RESET + "\n";
    int available = Math.max(36, Math.min(112, width)) - 4;
    boolean wide = width >= 90;
    int panel = wide ? (available - 4) / 2 : available;
    List<String> density = new ArrayList<>(ResultDetails.distribution(scores, panel));
    density.set(0, DIM + (startup ? "Launch distribution" : "Window distribution") + RESET);
    List<String> history =
        new ArrayList<>(
            ResultDetails.sequence(
                scores,
                panel,
                startup
                    ? java.util.stream.IntStream.rangeClosed(1, scores.size())
                        .mapToObj(i -> (double) i)
                        .toList()
                    : positions));
    history.set(0, VIOLET + (startup ? "Launch order" : "Soak · window range") + RESET);
    List<String> left = startup ? density : history, right = startup ? history : density;
    StringBuilder text = new StringBuilder();
    int lines = Math.max(left.size(), right.size());
    for (int i = 0; i < (wide ? lines : left.size()); i++) {
      String line = i < left.size() ? left.get(i) : "";
      text.append("    ").append(line);
      if (wide)
        text.append(" ".repeat(Math.max(0, panel - visible(line)) + 4))
            .append(i < right.size() ? right.get(i) : "");
      text.append('\n');
    }
    String leftCaption = startup ? units : axis;
    String rightCaption = startup ? axis : units;
    text.append("    ").append(DIM).append(leftCaption).append(RESET);
    if (wide)
      text.append(" ".repeat(Math.max(0, panel - leftCaption.length()) + 4))
          .append(DIM)
          .append(rightCaption)
          .append(RESET);
    text.append("\n");
    if (!wide) {
      for (String line : right) text.append("    ").append(line).append('\n');
      text.append("    ").append(DIM).append(startup ? axis : units).append(RESET).append("\n");
    }
    if (startup) {
      List<Double> sorted = scores.stream().sorted().toList();
      text.append("    ")
          .append(DIM)
          .append("median ")
          .append(CYAN)
          .append(ResultDetails.number(median(sorted)))
          .append(DIM)
          .append("  p95 ")
          .append(TEXT)
          .append(ResultDetails.number(ResultDetails.quantile(sorted, .95)))
          .append(DIM)
          .append("  ")
          .append(scores.size())
          .append(" launches")
          .append(RESET)
          .append('\n');
    } else {
      int quarter = Math.max(1, scores.size() / 4);
      text.append("    ")
          .append(DIM)
          .append(scores.size() < 4 ? "First → last window" : "First → last quarter median")
          .append(RESET)
          .append('\n')
          .append("    ")
          .append(CYAN)
          .append(ResultDetails.number(median(scores.subList(0, quarter))))
          .append(DIM)
          .append(" → ")
          .append(VIOLET)
          .append(
              ResultDetails.number(median(scores.subList(scores.size() - quarter, scores.size()))))
          .append(DIM)
          .append(" · ")
          .append(scores.size())
          .append(" windows retained")
          .append(RESET)
          .append('\n');
    }
    return text.toString();
  }

  private static double median(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList();
    return sorted.get((sorted.size() - 1) / 2) / 2 + sorted.get(sorted.size() / 2) / 2;
  }

  private static int visible(String value) {
    return value.replaceAll("\033\\[[0-9;]*m", "").length();
  }
}
