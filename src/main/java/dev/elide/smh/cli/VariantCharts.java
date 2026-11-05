// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static dev.elide.smh.cli.TerminalDisplay.*;

import com.google.gson.*;
import java.util.*;

/** Shared-axis distributions and histories for compatible variants; no pooled observations. */
final class VariantCharts {
  private static final String[] COLORS = {CYAN, VIOLET, GOLD, GREEN, ROSE};
  private static final String SYMBOLS = "●◆▲■▼";

  private VariantCharts() {}

  static List<List<JsonObject>> groups(JsonArray rows) {
    Map<String, List<JsonObject>> groups = new LinkedHashMap<>();
    for (JsonElement element : rows) {
      JsonObject row = element.getAsJsonObject(), key = new JsonObject();
      for (String field :
          List.of("benchmark", "mode", "vmName", "jdkVersion", "threads", "measurementBatchSize"))
        key.add(field, row.get(field));
      key.add("unit", row.getAsJsonObject("primaryMetric").get("scoreUnit"));
      JsonObject smh = row.has("smh") ? row.getAsJsonObject("smh") : new JsonObject();
      for (String field :
          List.of(
              "backend", "experimentMode", "endpoint", "boundary", "execution", "simulationModel"))
        key.add(field, smh.get(field));
      groups.computeIfAbsent(key.toString(), unused -> new ArrayList<>()).add(row);
    }
    return List.copyOf(groups.values());
  }

  static List<Double> samples(JsonObject row) {
    List<Double> result = new ArrayList<>();
    for (JsonElement fork : row.getAsJsonObject("primaryMetric").getAsJsonArray("rawData"))
      for (JsonElement value : fork.getAsJsonArray()) result.add(value.getAsDouble());
    return result;
  }

  static String render(List<JsonObject> rows, int width) {
    int cells = Math.clamp(width - 8, 24, 96);
    List<List<Double>> values = rows.stream().map(VariantCharts::samples).toList();
    var all = values.stream().flatMap(List::stream).toList();
    if (all.isEmpty() || all.stream().anyMatch(v -> !Double.isFinite(v)))
      return "  Incomplete variant observations; see raw data\n";
    double low = Collections.min(all), high = Collections.max(all);
    int rounds = values.stream().mapToInt(List::size).max().orElseThrow();
    String unit =
        ResultDetails.unit(
            rows.getFirst().getAsJsonObject("primaryMetric").get("scoreUnit").getAsString());
    JsonObject title = rows.getFirst().deepCopy();
    title.add("params", new JsonObject());
    StringBuilder out =
        new StringBuilder("\n  " + TEXT + TerminalDisplay.label(title, width - 4) + RESET + "\n");
    JsonObject metadata =
        rows.getFirst().has("smh") ? rows.getFirst().getAsJsonObject("smh") : new JsonObject();
    if (metadata.has("endpoint")) {
      String endpoint =
          switch (metadata.get("endpoint").getAsString()) {
            case "ready" -> "Ready";
            case "exit" -> "Full run";
            default -> "First result";
          };
      out.append("    ")
          .append(DIM)
          .append("Startup · ")
          .append(endpoint)
          .append(RESET)
          .append('\n');
    }
    int[][] histogram = new int[rows.size()][cells];
    int peak = 1;
    for (int i = 0; i < rows.size(); i++)
      for (double value : values.get(i)) {
        int bin = position(value, low, high, cells);
        peak = Math.max(peak, ++histogram[i][bin]);
      }
    for (int page = 0; page < rows.size(); page += COLORS.length) {
      int end = Math.min(rows.size(), page + COLORS.length);
      out.append("    ")
          .append(DIM)
          .append("Distribution · shared bins")
          .append(RESET)
          .append('\n');
      for (int y = 3; y >= 0; y--) {
        out.append("    ");
        for (int x = 0; x < cells; x++) {
          List<Integer> owners = new ArrayList<>();
          for (int i = page; i < end; i++)
            if (histogram[i][x] > 0 && (histogram[i][x] * 4L + peak - 1) / peak > y)
              owners.add(i - page);
          out.append(mark(owners, true));
        }
        out.append('\n');
      }
      out.append("    ")
          .append(DIM)
          .append(ResultDetails.number(low))
          .append(" → ")
          .append(ResultDetails.number(high))
          .append(' ')
          .append(unit)
          .append(RESET)
          .append('\n');
      out.append("    ")
          .append(DIM)
          .append("Sample order · 1 → " + rounds)
          .append(RESET)
          .append('\n');
      for (int y = 3; y >= 0; y--) {
        out.append("    ");
        for (int x = 0; x < cells; x++) {
          List<Integer> owners = new ArrayList<>();
          for (int i = page; i < end; i++) {
            for (int j = 0; j < values.get(i).size(); j++)
              if (position(j, 0, Math.max(1, rounds - 1), cells) == x
                  && position(values.get(i).get(j), low, high, 4) == y) {
                owners.add(i - page);
                break;
              }
          }
          out.append(mark(owners, false));
        }
        out.append('\n');
      }
      for (int i = page; i < end; i++) {
        JsonObject row = rows.get(i);
        String label =
            row.has("params")
                ? row.getAsJsonObject("params").entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().getAsString())
                    .collect(java.util.stream.Collectors.joining(" · "))
                : "variant " + (i + 1);
        String shortLabel = shorten(label, width - 6);
        String statistic =
            ResultDetails.number(row.getAsJsonObject("primaryMetric").get("score").getAsDouble())
                + " · mean · n="
                + values.get(i).size();
        out.append("    ")
            .append(COLORS[i - page])
            .append(SYMBOLS.charAt(i - page))
            .append(' ')
            .append(shortLabel)
            .append(RESET);
        out.append(6 + shortLabel.length() + 2 + statistic.length() > width ? "\n      " : "  ");
        out.append(statistic).append('\n');
      }
    }
    out.append("    ")
        .append(DIM)
        .append("◇ overlap · all samples retained")
        .append(RESET)
        .append('\n');
    for (JsonObject row : rows) out.append(ResultDetails.conditions(row));
    return out.toString();
  }

  private static String shorten(String text, int limit) {
    String safe = clean(text);
    return safe.length() > limit ? safe.substring(0, limit - 1) + "…" : safe;
  }

  private static int position(double value, double low, double high, int size) {
    return high == low
        ? size / 2
        : Math.clamp((int) ((value - low) / (high - low) * (size - 1)), 0, size - 1);
  }

  private static String mark(List<Integer> owners, boolean histogram) {
    if (owners.isEmpty()) return " ";
    if (owners.size() > 1) return TEXT + "◇" + RESET;
    int owner = owners.getFirst();
    return COLORS[owner] + (histogram ? "█" : SYMBOLS.substring(owner, owner + 1)) + RESET;
  }
}
