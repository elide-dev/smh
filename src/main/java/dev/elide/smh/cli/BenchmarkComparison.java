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

  private final Map<Key, Double> scores = new HashMap<>();
  private final Set<String> origins = new LinkedHashSet<>();

  static BenchmarkComparison read(Path file) throws Exception {
    return new BenchmarkComparison(JsonParser.parseString(Files.readString(file)).getAsJsonArray());
  }

  BenchmarkComparison(JsonArray rows) {
    if (rows.isEmpty()) throw new IllegalArgumentException("Baseline has no benchmark results");
    for (JsonElement element : rows) {
      JsonObject row = element.getAsJsonObject();
      Key key = key(row);
      double score = score(row);
      JsonObject execution = row.getAsJsonObject("smh").getAsJsonObject("execution");
      origins.add(
          execution == null || !execution.has("backend")
              ? "SMH"
              : execution.get("harness").getAsString().toUpperCase(Locale.ROOT)
                  + " · "
                  + execution.get("backend").getAsString()
                  + (execution.has("aotCacheKey") ? " · Leyden" : ""));
      if (scores.putIfAbsent(key, score) != null)
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

  private static double score(JsonObject row) {
    double score = row.getAsJsonObject("primaryMetric").get("score").getAsDouble();
    if (!Double.isFinite(score) || score < 0)
      throw new IllegalArgumentException("Comparison requires finite, nonnegative scores");
    return score;
  }

  /** Returns a signed percentage; empty means there is no matching, nonzero baseline. */
  OptionalDouble change(JsonObject row) {
    Double previous = scores.get(key(row));
    double current = score(row);
    if (previous == null || previous == 0) return OptionalDouble.empty();
    double percent = (current / previous - 1) * 100;
    return Double.isFinite(percent) ? OptionalDouble.of(percent) : OptionalDouble.empty();
  }
}
