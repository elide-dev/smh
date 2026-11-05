// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static dev.elide.smh.cli.TerminalDisplay.*;

import com.google.gson.*;
import java.math.*;
import java.util.*;

/** Descriptive views of every retained iteration score, outside measured work. */
final class ResultDetails {
  private static final int HEIGHT = 8;
  private static final String BLOCKS = " ▁▂▃▄▅▆▇█";

  private ResultDetails() {}

  static String unit(String unit) {
    return clean(unit.replace("us", "µs"));
  }

  static String render(JsonObject row, List<Double> samples) {
    return render(row, samples, 80);
  }

  static String render(JsonObject row, List<Double> samples, int width) {
    if (row.has("smh")
        && row.getAsJsonObject("smh").has("experimentMode")
        && !row.getAsJsonObject("smh").get("experimentMode").getAsString().equals("steady"))
      return ExperimentVisuals.render(row, samples, width);
    List<Double> finite = samples.stream().filter(Double::isFinite).toList();
    StringBuilder result = new StringBuilder();
    if (!finite.isEmpty()) {
      int available = Math.max(36, Math.min(160, width)) - 4;
      boolean wide = width >= 110;
      int panel = wide ? (available - 4) / 2 : available;
      List<String> distribution = distribution(finite, panel);
      List<String> sequence = wide ? sequence(finite, panel) : List.of();
      for (int i = 0; i < distribution.size(); i++) {
        String left = distribution.get(i);
        result.append("    ").append(left);
        if (wide) {
          result.append(" ".repeat(Math.max(0, panel - visible(left)) + 4));
          result.append(sequence.get(i));
        }
        result.append('\n');
      }
      String units =
          row.has("primaryMetric")
              ? " · " + unit(row.getAsJsonObject("primaryMetric").get("scoreUnit").getAsString())
              : "";
      String caption = "Iteration score" + units;
      result.append("    ").append(DIM).append(caption);
      if (wide)
        result
            .append(" ".repeat(Math.max(0, panel - caption.length()) + 4))
            .append(finite.size() == samples.size() ? "Iteration index" : "Finite-score index");
      result.append(RESET).append("\n\n");
      List<Double> sorted = finite.stream().sorted().toList();
      double lower = sorted.get((sorted.size() - 1) / 2);
      double upper = sorted.get(sorted.size() / 2);
      double sum = lower + upper;
      double median = Double.isFinite(sum) ? sum / 2 : lower / 2 + upper / 2;
      int used = 0;
      for (String statistic :
          List.of(
              DIM + "median " + TEXT + number(median),
              DIM + "p99 " + TEXT + number(quantile(sorted, .99)),
              DIM
                  + compactCount(finite.size())
                  + (finite.size() == 1 ? " iteration" : " iterations"))) {
        int length = visible(statistic);
        if (used > 0 && used + 3 + length > available) {
          result.append(RESET).append('\n');
          used = 0;
        }
        result.append(used == 0 ? "    " : "   ").append(statistic);
        used += (used == 0 ? 0 : 3) + length;
      }
      result.append(RESET).append('\n');
    } else {
      result
          .append("    ")
          .append(GOLD)
          .append("No finite iteration scores")
          .append(RESET)
          .append('\n');
    }
    if (finite.size() != samples.size())
      result
          .append("    ")
          .append(GOLD)
          .append(samples.size() - finite.size())
          .append(" nonfinite scores omitted")
          .append(RESET)
          .append('\n');
    return result.append(conditions(row)).append('\n').toString();
  }

  /** Equal-width bins include both endpoints. Constant series occupy the central bin. */
  static int[] histogram(List<Double> samples, int bins) {
    double min = Collections.min(samples), max = Collections.max(samples);
    int[] counts = new int[bins];
    for (double sample : samples)
      counts[Math.min(bins - 1, (int) (fraction(sample, min, max) * bins))]++;
    return counts;
  }

  static List<String> distribution(List<Double> samples, int width) {
    double min = Collections.min(samples), max = Collections.max(samples);
    int labelWidth = Integer.toString(samples.size()).length();
    int columns = (width - labelWidth - 2) / 2;
    int[] counts = histogram(samples, columns);
    int peak = Arrays.stream(counts).max().orElseThrow();
    int plotWidth = columns * 2;
    List<String> lines = new ArrayList<>();
    lines.add(DIM + "Iterations" + RESET);
    for (int y = 0; y < HEIGHT; y++) {
      String tick = y == 0 ? Integer.toString(peak) : "";
      StringBuilder line =
          new StringBuilder(DIM + " ".repeat(labelWidth - tick.length()) + tick + " │");
      for (int count : counts) {
        // Ceil keeps every nonempty tail visible, even below one terminal subpixel.
        double level = (double) count / peak * HEIGHT - (HEIGHT - y - 1);
        int eighths = (int) Math.clamp(Math.ceil(level * 8), 0, 8);
        if (y == HEIGHT - 1 && count > 0) eighths = Math.max(1, eighths);
        line.append(CYAN).append(String.valueOf(BLOCKS.charAt(eighths)).repeat(2));
      }
      lines.add(line.append(RESET).toString());
    }
    lines.add(DIM + " ".repeat(labelWidth - 1) + "0 └" + "─".repeat(plotWidth) + RESET);
    List<String> axis = axis(min, max, plotWidth);
    for (String line : axis) lines.add(DIM + " ".repeat(labelWidth + 2) + line + RESET);
    return lines;
  }

  static List<String> sequence(List<Double> samples, int width) {
    return sequence(samples, width, null);
  }

  static List<String> sequence(List<Double> samples, int width, List<Double> positions) {
    double min = Collections.min(samples), max = Collections.max(samples);
    String top = number(max), bottom = number(min);
    if (max != min && top.equals(bottom)) {
      top = Double.toString(max);
      bottom = Double.toString(min);
    }
    int labelWidth = Math.max(top.length(), bottom.length());
    int columns =
        positions == null
            ? Math.min(samples.size(), width - labelWidth - 2)
            : width - labelWidth - 2;
    int[] low = new int[columns], high = new int[columns];
    Arrays.fill(low, HEIGHT - 1);
    for (int i = 0; i < samples.size(); i++) {
      int column =
          positions == null
              ? (int) ((long) i * columns / samples.size())
              : Math.min(
                  columns - 1,
                  (int) (fraction(positions.get(i), 0, positions.getLast()) * columns));
      int level = (int) Math.round(fraction(samples.get(i), min, max) * (HEIGHT - 1));
      low[column] = Math.min(low[column], level);
      high[column] = Math.max(high[column], level);
    }
    List<String> lines = new ArrayList<>();
    lines.add(DIM + "Run order · min–max per column" + RESET);
    for (int y = 0; y < HEIGHT; y++) {
      String tick = y == 0 ? top : y == HEIGHT - 1 ? bottom : "";
      StringBuilder line =
          new StringBuilder(DIM + " ".repeat(labelWidth - tick.length()) + tick + " │" + VIOLET);
      int level = HEIGHT - y - 1;
      for (int x = 0; x < columns; x++) {
        boolean occupied = level >= low[x] && level <= high[x];
        line.append(occupied ? low[x] == high[x] ? '●' : '┃' : ' ');
      }
      lines.add(line.append(RESET).toString());
    }
    lines.add(DIM + " ".repeat(labelWidth + 1) + "└" + "─".repeat(columns) + RESET);
    if (positions != null) {
      for (String tick : axis(0, positions.getLast(), columns))
        lines.add(DIM + " ".repeat(labelWidth + 2) + tick + RESET);
      return lines;
    }
    lines.add(
        DIM
            + " ".repeat(labelWidth + 2)
            + "1"
            + (columns > 1
                ? " ".repeat(Math.max(1, columns - Integer.toString(samples.size()).length() - 1))
                    + samples.size()
                : "")
            + RESET);
    return lines;
  }

  /** Normalize without overflowing when finite endpoints have an infinite difference. */
  private static double fraction(double value, double min, double max) {
    if (max == min) return .5;
    double span = max - min;
    return Double.isFinite(span)
        ? (value - min) / span
        : (value / 2 - min / 2) / (max / 2 - min / 2);
  }

  private static List<String> axis(double min, double max, int width) {
    String left = number(min), right = number(max);
    if (min == max) return List.of(" ".repeat(Math.max(0, (width - left.length()) / 2)) + left);
    // Adjacent floating-point values need enough digits to distinguish the actual endpoints.
    if (left.equals(right)) {
      left = Double.toString(min);
      right = Double.toString(max);
    }
    if (left.length() + right.length() + 1 > width)
      return List.of(left, " ".repeat(Math.max(0, width - right.length())) + right);
    return List.of(left + " ".repeat(width - left.length() - right.length()) + right);
  }

  private static int visible(String value) {
    return value.replaceAll("\033\\[[0-9;]*m", "").length();
  }

  static String conditions(JsonObject row) {
    if (!row.has("smh")) return "";
    StringBuilder result = new StringBuilder();
    JsonObject smh = row.getAsJsonObject("smh");
    for (String name : List.of("warmup", "measurement")) {
      if (!smh.has(name)) continue;
      JsonObject phase = smh.getAsJsonObject(name);
      String status = phase.has("status") ? phase.get("status").getAsString() : "not-assessed";
      String reason = phase.has("stoppingReason") ? phase.get("stoppingReason").getAsString() : "";
      boolean quietStatus =
          Set.of("not-assessed", "unknown", "stable").contains(status.toLowerCase(Locale.ROOT));
      boolean success =
          Set.of("", "fixed-count", "converged", "duration-reached", "operations-reached")
              .contains(reason.toLowerCase(Locale.ROOT));
      if (quietStatus && success) continue;
      result
          .append("    ")
          .append(GOLD)
          .append(
              clean(
                  name
                      + " · "
                      + (success ? status : reason)
                          .toLowerCase(Locale.ROOT)
                          .replace('_', ' ')
                          .replace('-', ' ')))
          .append(RESET)
          .append('\n');
    }
    return result.toString();
  }

  static String number(double value) {
    if (!Double.isFinite(value)) return "—";
    if (value == 0) return "0";
    BigDecimal rounded = BigDecimal.valueOf(value).round(new MathContext(3)).stripTrailingZeros();
    double magnitude = Math.abs(rounded.doubleValue());
    if (magnitude < .001 || magnitude >= 1e6) {
      int exponent = rounded.precision() - rounded.scale() - 1;
      return rounded.scaleByPowerOfTen(-exponent).toPlainString()
          + "e"
          + (exponent < 0 ? "−" : "+")
          + Math.abs(exponent);
    }
    return rounded.toPlainString();
  }

  static String compactCount(long count) {
    if (count >= 1_000_000) return number(count / 1e6) + "m";
    if (count >= 1_000) return number(count / 1e3) + "k";
    return Long.toString(count);
  }

  static double quantile(List<Double> sorted, double probability) {
    return sorted.get(Math.max(0, (int) Math.ceil(probability * sorted.size()) - 1));
  }
}
