// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/**
 * Descriptive score changes only. Matching identities do not establish experimental comparability.
 */
final class BenchmarkComparison {
  private record Key(
      String benchmark,
      String mode,
      JsonObject params,
      String unit,
      String experiment,
      JsonElement endpoint,
      JsonElement model) {}

  /** Median and median absolute deviation of retained samples; the score when none exist. */
  record Stats(double score, double median, double spread, int samples) {
    boolean dispersed() {
      return samples > 0 && Math.abs(median) > 0;
    }
  }

  private final Map<Key, Stats> baseline = new HashMap<>();
  private final Set<String> origins = new LinkedHashSet<>();

  static BenchmarkComparison read(Path file) throws Exception {
    return new BenchmarkComparison(JsonParser.parseString(Files.readString(file)).getAsJsonArray());
  }

  BenchmarkComparison(JsonArray rows) {
    if (rows.isEmpty()) throw new IllegalArgumentException("Baseline has no benchmark results");
    for (JsonElement element : rows) {
      JsonObject row = element.getAsJsonObject();
      Key key = key(row);
      Stats stats = stats(row);
      JsonObject execution = row.getAsJsonObject("smh").getAsJsonObject("execution");
      origins.add(
          execution == null || !execution.has("backend")
              ? "SMH"
              : execution.get("harness").getAsString().toUpperCase(Locale.ROOT)
                  + " · "
                  + execution.get("backend").getAsString()
                  + (execution.has("aotCacheKey") ? " · Leyden" : ""));
      if (baseline.putIfAbsent(key, stats) != null)
        throw new IllegalArgumentException("Duplicate benchmark identity in baseline");
    }
  }

  String description() {
    return String.join(" / ", origins);
  }

  private static Key key(JsonObject row) {
    JsonObject smh = row.getAsJsonObject("smh");
    if (smh == null
        || !("wall".equals(smh.get("backend").getAsString())
            || new JsonPrimitive("callgrind").equals(smh.get("backend"))
                && new JsonPrimitive("startup").equals(smh.get("experimentMode"))
                && smh.has("simulationModel")))
      throw new IllegalArgumentException(
          "Comparison requires SMH wall, startup simulation, or adapted JMH results");
    JsonObject execution = smh.getAsJsonObject("execution");
    if (execution != null
        && execution.has("purpose")
        && !execution.get("purpose").getAsString().equals("measurement"))
      throw new IllegalArgumentException(
          "Diagnostic and training results cannot be used for performance deltas");
    return new Key(
        row.get("benchmark").getAsString(),
        row.get("mode").getAsString(),
        row.getAsJsonObject("params"),
        row.getAsJsonObject("primaryMetric").get("scoreUnit").getAsString(),
        smh.has("experimentMode") ? smh.get("experimentMode").getAsString() : "steady",
        smh.get("endpoint"),
        smh.get("simulationModel"));
  }

  static Stats stats(JsonObject row) {
    JsonObject metric = row.getAsJsonObject("primaryMetric");
    double score = metric.get("score").getAsDouble();
    if (!Double.isFinite(score) || score < 0)
      throw new IllegalArgumentException("Comparison requires finite, nonnegative scores");
    List<Double> samples = new ArrayList<>();
    if (metric.has("rawData"))
      for (JsonElement fork : metric.getAsJsonArray("rawData"))
        for (JsonElement value : fork.getAsJsonArray()) {
          double sample = value.getAsDouble();
          if (Double.isFinite(sample)) samples.add(sample);
        }
    if (samples.isEmpty()) return new Stats(score, score, Double.NaN, 0);
    List<Double> sorted = samples.stream().sorted().toList();
    double median = ResultDetails.median(sorted);
    return new Stats(score, median, ResultDetails.mad(sorted, median), sorted.size());
  }

  /** Signed percentage change of the median; empty without a matching, nonzero baseline. */
  OptionalDouble change(JsonObject row) {
    Stats previous = baseline.get(key(row));
    Stats current = stats(row);
    if (previous == null || previous.median() == 0) return OptionalDouble.empty();
    double percent = (current.median() / previous.median() - 1) * 100;
    return Double.isFinite(percent) ? OptionalDouble.of(percent) : OptionalDouble.empty();
  }

  /**
   * Descriptive noise band in percent: the sum of both runs' median absolute deviations relative to
   * their medians. A change inside this band is indistinguishable from iteration spread; this is
   * not a significance test.
   */
  OptionalDouble spread(JsonObject row) {
    Stats previous = baseline.get(key(row));
    Stats current = stats(row);
    if (previous == null || !previous.dispersed() || !current.dispersed())
      return OptionalDouble.empty();
    double percent =
        (previous.spread() / Math.abs(previous.median())
                + current.spread() / Math.abs(current.median()))
            * 100;
    return Double.isFinite(percent) ? OptionalDouble.of(percent) : OptionalDouble.empty();
  }

  boolean withinSpread(JsonObject row) {
    OptionalDouble change = change(row), spread = spread(row);
    return change.isPresent()
        && spread.isPresent()
        && Math.abs(change.getAsDouble()) <= spread.getAsDouble();
  }

  /** Baseline median for the row, when one matches. */
  OptionalDouble previous(JsonObject row) {
    Stats previous = baseline.get(key(row));
    return previous == null ? OptionalDouble.empty() : OptionalDouble.of(previous.median());
  }

  /** Whether a positive change means faster for this row's score direction. */
  static boolean higherIsBetter(JsonObject row) {
    return "thrpt".equals(row.get("mode").getAsString());
  }
}
