// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static dev.elide.smh.cli.TerminalDisplay.*;

import com.google.gson.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Parent-process views of imported simulated events; never a hardware timing result. */
final class SimulationDisplay {
  private static final MathContext PRECISION = MathContext.DECIMAL128;
  private static final Map<String, String> EVENTS =
      Map.of(
          "Ir",
          "Instructions",
          "Dr",
          "Data reads",
          "Dw",
          "Data writes",
          "D1mr",
          "D1 read misses",
          "DLmr",
          "Last-level read misses",
          "Bcm",
          "Conditional branch misses");
  private static final List<String> ORDER = List.of("Ir", "Dr", "Dw", "D1mr", "DLmr", "Bcm");
  private static final List<String> PAIRS = List.of("Ir", "Dr", "Dw", "Bcm", "D1mr", "DLmr");

  private record Key(String benchmark, JsonObject params) {}

  private SimulationDisplay() {}

  static JsonObject read(Path file) throws IOException {
    try {
      JsonObject report = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
      rows(report);
      return report;
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(
          "Baseline requires collected SMH Callgrind results", invalid);
    }
  }

  static boolean visual(Mode mode, boolean terminal, Map<String, String> env, JsonObject baseline) {
    boolean visual = enabled(mode, terminal, env);
    if (baseline != null && !visual)
      throw new IllegalArgumentException("--compare requires a terminal or --display tui");
    return visual;
  }

  static void publish(
      JsonObject report,
      JsonObject baseline,
      Mode mode,
      boolean terminal,
      Map<String, String> env,
      PrintStream out,
      PrintStream err,
      Path directory) {
    boolean visual = visual(mode, terminal, env, baseline);
    // Match the historical single-line JSON exactly when stdout is redirected or plain.
    if (!visual || !terminal) out.println(report);
    if (!visual) return;
    try {
      render(report, baseline, err, width(env));
    } catch (RuntimeException invalid) {
      err.println("  No simulation summary available; see raw report.");
    }
    err.println("  " + DIM + clean(path(directory.resolve("results.json"))) + RESET);
  }

  static void render(JsonObject report, JsonObject baseline, PrintStream out, int width) {
    Map<Key, JsonObject> current = rows(report);
    Map<Key, JsonObject> previous = baseline == null ? Map.of() : rows(baseline);
    String model = baseline == null ? "" : modelDifference(report, baseline);
    int cells = Math.clamp(width - 31, 12, 48);
    StringBuilder text = new StringBuilder();
    int unmatched = 0;
    for (var entry : current.entrySet()) {
      JsonObject row = entry.getValue(), before = previous.get(entry.getKey());
      String mismatch = model;
      if (baseline != null && mismatch.isEmpty()) {
        if (before == null) {
          mismatch = "no matching baseline";
          unmatched++;
        } else mismatch = workDifference(row, before);
      }
      if (!mismatch.isEmpty()) before = null;
      String params =
          entry.getKey().params.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue().getAsString())
              .collect(Collectors.joining(" · "));
      text.append('\n')
          .append("  ")
          .append(TEXT)
          .append(fit(clean(entry.getKey().benchmark + " · " + params), width - 4))
          .append(RESET)
          .append("\n  ")
          .append(EDGE)
          .append("─".repeat(Math.clamp(width - 4, 24, 96)))
          .append(RESET)
          .append("\n  ")
          .append(DIM)
          .append("Callgrind · simulated events")
          .append(RESET)
          .append("\n\n    ")
          .append(BOLD)
          .append(CYAN)
          .append(number(mean(row, "Ir")))
          .append(" instructions/op")
          .append(RESET);
      if (before != null)
        text.append(width < 64 ? "\n    " : "   ")
            .append(VIOLET)
            .append(change(mean(row, "Ir"), mean(before, "Ir")))
            .append(" observed")
            .append(RESET);
      text.append("\n\n");
      BigDecimal secondaryScale = BigDecimal.ZERO;
      Set<String> events =
          row.getAsJsonArray("observations")
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("events")
              .keySet();
      boolean soak = new JsonPrimitive("soak").equals(row.get("experimentMode"));
      if (soak) text.append(ExperimentVisuals.simulationSoak(row, width));
      if (before == null) {
        if (!soak) trace(text, row, cells);
        for (String event : ORDER)
          if (!event.equals("Ir") && events.contains(event))
            secondaryScale = secondaryScale.max(mean(row, event));
        text.append("    ")
            .append(DIM)
            .append("Other events/op · scale 0 → ")
            .append(number(secondaryScale))
            .append(RESET)
            .append("\n\n");
      }
      List<String> shownEvents =
          before == null
              ? ORDER.stream().filter(e -> !e.equals("Ir") && events.contains(e)).toList()
              : PAIRS.stream().filter(events::contains).limit(4).toList();
      for (String event : shownEvents) {
        BigDecimal now = mean(row, event), old = before == null ? null : mean(before, event);
        BigDecimal ceiling = old == null ? secondaryScale : now.max(old);
        if (old == null) {
          int labelWidth = Math.min(25, width / 3);
          bar(
              text,
              String.format(
                  Locale.ROOT, "%-" + labelWidth + "s", fit(EVENTS.get(event), labelWidth)),
              now,
              ceiling,
              Math.clamp(width - labelWidth - 18, 6, 36),
              CYAN);
        } else {
          text.append("    ")
              .append(DIM)
              .append(fit(EVENTS.get(event) + "/op", width - 6))
              .append(RESET)
              .append('\n');
          bar(text, "baseline", old, ceiling, cells, VIOLET);
          bar(text, "current", now, ceiling, cells, CYAN);
          text.append('\n');
        }
      }
      int count = row.getAsJsonArray("observations").size();
      text.append("\n    ")
          .append(DIM)
          .append(count)
          .append(" iterations · ")
          .append(number(new BigDecimal(row.get("operationsPerIteration").getAsString())))
          .append(" ops each\n    ")
          .append(repeatability(row))
          .append(RESET)
          .append('\n');
      if (!mismatch.isEmpty())
        text.append("    ")
            .append(GOLD)
            .append(width < 64 ? "Comparison unavailable:\n    " : "Comparison unavailable: ")
            .append(
                width < 64
                    ? mismatch.replace(" differs or is missing", "\n    differs or is missing")
                    : mismatch)
            .append(RESET)
            .append('\n');
    }
    text.append("\n  ")
        .append(DIM)
        .append("Event bars start at zero.")
        .append("\n  Simulated · accuracy unqualified")
        .append(RESET)
        .append('\n');
    if (report.has("purpose"))
      text.append("  ")
          .append(DIM)
          .append(fit(clean(report.get("purpose").getAsString()), width - 4))
          .append(RESET)
          .append('\n');
    if (baseline != null) {
      text.append("  ")
          .append(DIM)
          .append("Host/build equivalence unchecked")
          .append(RESET)
          .append('\n');
      long unused = previous.keySet().stream().filter(k -> !current.containsKey(k)).count();
      if (unused > 0 || unmatched > 0)
        text.append("  ")
            .append(GOLD)
            .append(unmatched)
            .append(" current / ")
            .append(unused)
            .append(" baseline unmatched")
            .append(RESET)
            .append('\n');
    }
    out.print(text);
  }

  static Map<Key, JsonObject> rows(JsonObject report) {
    if (report.get("schemaVersion").getAsInt() != 1
        || !"callgrind".equals(report.get("backend").getAsString())
        || !"simulated-events".equals(report.get("quantity").getAsString()))
      throw new IllegalArgumentException("Expected a Callgrind simulation report");
    Map<Key, JsonObject> rows = new LinkedHashMap<>();
    for (JsonElement element : report.getAsJsonArray("results")) {
      JsonObject row = element.getAsJsonObject();
      if (!"collected".equals(row.get("status").getAsString())
          || !"callgrind".equals(row.get("backend").getAsString()))
        throw new IllegalArgumentException("Simulation counters were not collected");
      JsonObject params = row.getAsJsonObject("params");
      Objects.requireNonNull(params, "Missing benchmark parameters");
      Key key = new Key(row.get("benchmark").getAsString(), params);
      if (rows.putIfAbsent(key, row) != null)
        throw new IllegalArgumentException("Duplicate simulation benchmark identity");
      JsonArray observations = row.getAsJsonArray("observations");
      BigInteger operations =
          row.get("operationsPerIteration").getAsBigDecimal().toBigIntegerExact();
      if (observations.isEmpty()
          || observations.size()
              != row.get("measurementIterations").getAsBigDecimal().intValueExact()
          || operations.signum() <= 0)
        throw new IllegalArgumentException("Invalid simulation work budget");
      Set<String> events = observations.get(0).getAsJsonObject().getAsJsonObject("events").keySet();
      if (!events.contains("Ir")) throw new IllegalArgumentException("Missing instructions");
      BigInteger totalOperations = BigInteger.ZERO;
      for (JsonElement sample : observations) {
        JsonObject observation = sample.getAsJsonObject(),
            counters = observation.getAsJsonObject("events");
        BigInteger window = observation.get("operations").getAsBigDecimal().toBigIntegerExact();
        totalOperations = totalOperations.add(window);
        boolean soak = new JsonPrimitive("soak").equals(row.get("experimentMode"));
        if ((soak
                ? window.signum() <= 0 || window.compareTo(operations) > 0
                : !operations.equals(window))
            || !events.equals(counters.keySet()))
          throw new IllegalArgumentException("Inconsistent simulation observations");
        for (JsonElement counter : counters.asMap().values())
          if (counter.getAsBigDecimal().toBigIntegerExact().signum() < 0)
            throw new IllegalArgumentException("Negative event count");
      }
      if (new JsonPrimitive("soak").equals(row.get("experimentMode"))
          && !totalOperations.equals(
              row.get("requestedOperations").getAsBigDecimal().toBigIntegerExact()))
        throw new IllegalArgumentException("Soak operation budget was not completed");
    }
    if (rows.isEmpty()) throw new IllegalArgumentException("No simulation results");
    return rows;
  }

  static String modelDifference(JsonObject current, JsonObject baseline) {
    for (String field : List.of("creator", "cacheModel", "architecture", "purpose"))
      if (!current.has(field)
          || !baseline.has(field)
          || !current.get(field).equals(baseline.get(field)))
        return field + " differs or is missing";
    return "";
  }

  static String workDifference(JsonObject current, JsonObject baseline) {
    String currentMode =
        current.has("experimentMode") ? current.get("experimentMode").getAsString() : "steady";
    String baselineMode =
        baseline.has("experimentMode") ? baseline.get("experimentMode").getAsString() : "steady";
    if (!currentMode.equals(baselineMode)) return "experimentMode differs or is missing";
    for (String field : List.of("requestedOperations", "operationCounts"))
      if (!Objects.equals(current.get(field), baseline.get(field)))
        return field + " differs or is missing";
    for (String field :
        List.of(
            "region",
            "sink",
            "warmupIterations",
            "measurementIterations",
            "invocationsPerIteration",
            "operationsPerIteration"))
      if (!current.has(field)
          || !baseline.has(field)
          || !current.get(field).equals(baseline.get(field)))
        return field + " differs or is missing";
    if (!current
        .getAsJsonArray("observations")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("events")
        .keySet()
        .equals(
            baseline
                .getAsJsonArray("observations")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("events")
                .keySet())) return "event sets differ";
    return "";
  }

  static BigDecimal mean(JsonObject row, String event) {
    BigInteger total = BigInteger.ZERO, operations = BigInteger.ZERO;
    for (JsonElement sample : row.getAsJsonArray("observations")) {
      JsonObject observation = sample.getAsJsonObject();
      total =
          total.add(
              observation
                  .getAsJsonObject("events")
                  .get(event)
                  .getAsBigDecimal()
                  .toBigIntegerExact());
      operations =
          operations.add(observation.get("operations").getAsBigDecimal().toBigIntegerExact());
    }
    return new BigDecimal(total).divide(new BigDecimal(operations), PRECISION);
  }

  static String number(BigDecimal value) {
    double approximate = value.doubleValue();
    if (approximate >= 1e9 && approximate < 1e12)
      return ResultDetails.number(approximate / 1e9) + "b";
    if (approximate >= 1e6 && approximate < 1e9)
      return ResultDetails.number(approximate / 1e6) + "m";
    if (approximate >= 1000 && approximate < 1e6)
      return ResultDetails.number(approximate / 1000) + "k";
    return Double.isFinite(approximate) && (approximate != 0 || value.signum() == 0)
        ? ResultDetails.number(approximate)
        : value.round(new MathContext(3)).toString();
  }

  static String change(BigDecimal current, BigDecimal baseline) {
    if (baseline.signum() == 0) return "Δ unavailable (zero baseline)";
    BigDecimal percent =
        current.subtract(baseline).divide(baseline, PRECISION).multiply(BigDecimal.valueOf(100));
    if (percent.signum() == 0) return "unchanged";
    return (percent.signum() > 0 ? "+" : "−")
        + (percent.abs().compareTo(BigDecimal.ONE) < 0
            ? "<1"
            : number(percent.abs().setScale(0, RoundingMode.HALF_EVEN)))
        + "%";
  }

  static void bar(
      StringBuilder text, String label, BigDecimal value, BigDecimal max, int cells, String color) {
    int eighths =
        max.signum() == 0
            ? 0
            : value
                .divide(max, PRECISION)
                .multiply(BigDecimal.valueOf(cells * 8L))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    int full = eighths / 8, partial = eighths % 8;
    text.append("    ")
        .append(DIM)
        .append(String.format(Locale.ROOT, "%-9s", label))
        .append(EDGE)
        .append('│')
        .append(color)
        .append("█".repeat(full));
    if (partial != 0) text.append(" ▏▎▍▌▋▊▉".charAt(partial));
    text.append(EDGE)
        .append("─".repeat(cells - full - (partial == 0 ? 0 : 1)))
        .append(RESET)
        .append("  ")
        .append(color)
        .append(number(value))
        .append(RESET)
        .append('\n');
  }

  static void trace(StringBuilder text, JsonObject row, int cells) {
    List<BigDecimal> samples = new ArrayList<>();
    for (JsonElement value : row.getAsJsonArray("observations")) {
      JsonObject sample = value.getAsJsonObject();
      samples.add(
          sample
              .getAsJsonObject("events")
              .get("Ir")
              .getAsBigDecimal()
              .divide(sample.get("operations").getAsBigDecimal(), PRECISION));
    }
    BigDecimal min = Collections.min(samples),
        max = Collections.max(samples),
        range = max.subtract(min);
    text.append("    ")
        .append(DIM)
        .append("Instructions/op · iteration order")
        .append(RESET)
        .append("\n    ")
        .append(CYAN);
    int bins = Math.min(cells, samples.size());
    for (int i = 0; i < bins; i++) {
      // Retain peaks when grouping a long run; every observation contributes to a bin.
      BigDecimal peak =
          Collections.max(
              samples.subList(i * samples.size() / bins, (i + 1) * samples.size() / bins));
      int level =
          range.signum() == 0
              ? 3
              : peak.subtract(min)
                  .divide(range, PRECISION)
                  .multiply(BigDecimal.valueOf(7))
                  .setScale(0, RoundingMode.HALF_UP)
                  .intValue();
      text.append("▁▂▃▄▅▆▇█".charAt(level));
    }
    text.append(RESET)
        .append("  ")
        .append(DIM)
        .append(number(min))
        .append("–")
        .append(number(max))
        .append("\n    1 → ")
        .append(samples.size());
    if (samples.size() > cells) text.append(" · peak per group");
    text.append(RESET).append("\n\n");
  }

  static String repeatability(JsonObject row) {
    JsonArray observations = row.getAsJsonArray("observations");
    if (observations.size() == 1) return "Repeatability: not assessed";
    Set<BigInteger> counts = new HashSet<>();
    for (JsonElement sample : observations)
      counts.add(
          sample
              .getAsJsonObject()
              .getAsJsonObject("events")
              .get("Ir")
              .getAsBigDecimal()
              .toBigIntegerExact());
    return counts.size() == 1 ? "Instruction counts identical" : "Instruction counts vary";
  }
}
