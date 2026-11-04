// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Null overrides defer to benchmark annotations. Durations are nanoseconds. */
public final class RunConfig {
  public String include = ".*";
  public MeasurementBackend backend = MeasurementBackend.WALL;
  public Long invocationsPerIteration;
  public int wallBatchSize = 1;
  public BatchCalibrationPolicy batchCalibration;
  public Integer warmupIterations;
  public Integer measurementIterations;
  public Long warmupNanos;
  public Long measurementNanos;
  public StabilityPolicy warmupStability;
  public StabilityPolicy measurementStability;
  public List<String> modes;
  public final Map<String, List<String>> params = new LinkedHashMap<>();
  public Path output;

  public static RunConfig read(String path) throws Exception {
    if (path == null || path.isEmpty()) return new RunConfig();
    return fromJson(JsonParser.parseString(Files.readString(Path.of(path))));
  }

  /** Parse without filesystem access; reject shape/type errors rather than coercing JSON values. */
  public static RunConfig fromJson(JsonElement document) {
    JsonObject j = object(document);
    RunConfig c = new RunConfig();
    Set<String> allowed =
        Set.of(
            "include",
            "backend",
            "invocationsPerIteration",
            "wallBatchSize",
            "batchCalibration",
            "warmupIterations",
            "measurementIterations",
            "warmupNanos",
            "measurementNanos",
            "warmupStability",
            "measurementStability",
            "modes",
            "params",
            "output");
    for (String key : j.keySet())
      if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown run option: " + key);
    if (j.has("include")) c.include = text(j.get("include"));
    if (j.has("backend")) c.backend = MeasurementBackend.parse(text(j.get("backend")));
    if (j.has("invocationsPerIteration"))
      c.invocationsPerIteration = number(j, "invocationsPerIteration");
    if (j.has("wallBatchSize")) c.wallBatchSize = integer(j, "wallBatchSize");
    if (j.has("batchCalibration")) {
      JsonObject policy = object(j.get("batchCalibration"));
      if (!policy
          .keySet()
          .equals(
              Set.of("targetNanos", "maximumBatchSize", "maximumAttempts", "samplesPerAttempt")))
        throw new IllegalArgumentException(
            "Calibration policy must specify exactly its four bounds");
      c.batchCalibration =
          new BatchCalibrationPolicy(
              number(policy, "targetNanos"), integer(policy, "maximumBatchSize"),
              integer(policy, "maximumAttempts"), integer(policy, "samplesPerAttempt"));
    }
    if (j.has("warmupIterations")) c.warmupIterations = integer(j, "warmupIterations");
    if (j.has("measurementIterations"))
      c.measurementIterations = integer(j, "measurementIterations");
    if (j.has("warmupNanos")) c.warmupNanos = number(j, "warmupNanos");
    if (j.has("measurementNanos")) c.measurementNanos = number(j, "measurementNanos");
    if (j.has("warmupStability")) c.warmupStability = stability(object(j.get("warmupStability")));
    if (j.has("measurementStability"))
      c.measurementStability = stability(object(j.get("measurementStability")));
    if (j.has("modes")) c.modes = strings(j.get("modes"));
    if (j.has("params"))
      object(j.get("params"))
          .entrySet()
          .forEach(e -> c.params.put(e.getKey(), strings(e.getValue())));
    if (j.has("output")) c.output = Path.of(text(j.get("output")));
    c.validate();
    return c;
  }

  private static int integer(JsonObject j, String k) {
    return numeric(j.get(k)).getAsBigDecimal().intValueExact();
  }

  private static StabilityPolicy stability(JsonObject j) {
    Set<String> fields =
        Set.of(
            "minimumSamples",
            "maximumSamples",
            "windowSize",
            "consecutiveWindows",
            "maximumRelativeDeviation",
            "maximumRelativeDrift",
            "maximumRelativeSlope");
    if (!j.keySet().equals(fields))
      throw new IllegalArgumentException("Stability policy must specify exactly " + fields);
    return new StabilityPolicy(
        integer(j, "minimumSamples"),
        integer(j, "maximumSamples"),
        integer(j, "windowSize"),
        integer(j, "consecutiveWindows"),
        numeric(j.get("maximumRelativeDeviation")).getAsDouble(),
        numeric(j.get("maximumRelativeDrift")).getAsDouble(),
        numeric(j.get("maximumRelativeSlope")).getAsDouble());
  }

  private static long number(JsonObject j, String k) {
    return numeric(j.get(k)).getAsBigDecimal().longValueExact();
  }

  private static JsonObject object(JsonElement value) {
    if (value instanceof JsonObject object) return object;
    throw new IllegalArgumentException("Expected a JSON object");
  }

  private static String text(JsonElement value) {
    if (value instanceof JsonPrimitive primitive && primitive.isString())
      return primitive.getAsString();
    throw new IllegalArgumentException("Expected a JSON string");
  }

  private static JsonPrimitive numeric(JsonElement value) {
    if (value instanceof JsonPrimitive primitive && primitive.isNumber()) return primitive;
    throw new IllegalArgumentException("Expected a JSON number");
  }

  private static List<String> strings(JsonElement value) {
    if (!(value instanceof JsonArray a))
      throw new IllegalArgumentException("Expected a JSON array");
    List<String> r = new ArrayList<>();
    a.forEach(v -> r.add(text(v)));
    return r;
  }

  public void validate() {
    Objects.requireNonNull(backend, "backend");
    if (wallBatchSize < 1) throw new IllegalArgumentException("wallBatchSize must be positive");
    if (batchCalibration != null && (backend != MeasurementBackend.WALL || wallBatchSize != 1))
      throw new IllegalArgumentException(
          "Calibration requires wall mode and the default wallBatchSize");
    if (backend == MeasurementBackend.CALLGRIND) {
      if (invocationsPerIteration == null || invocationsPerIteration < 1)
        throw new IllegalArgumentException(
            "Callgrind requires a positive invocationsPerIteration budget");
      if (warmupNanos != null || measurementNanos != null || modes != null || wallBatchSize != 1)
        throw new IllegalArgumentException(
            "Simulation uses event counts and work budgets, not wall durations or modes");
      if (warmupStability != null || measurementStability != null)
        throw new IllegalArgumentException(
            "Online simulator convergence is not yet supported; use fixed iterations");
    } else if (invocationsPerIteration != null) {
      throw new IllegalArgumentException(
          "invocationsPerIteration currently requires a simulation backend");
    }
    java.util.regex.Pattern.compile(include);
    if (warmupStability != null && warmupIterations != null
        || measurementStability != null && measurementIterations != null)
      throw new IllegalArgumentException(
          "A phase cannot specify both a fixed count and a stability policy");
    if (warmupIterations != null && warmupIterations < 0
        || measurementIterations != null && measurementIterations < 1
        || warmupNanos != null && warmupNanos < 1
        || measurementNanos != null && measurementNanos < 1)
      throw new IllegalArgumentException(
          "Iterations/durations must be positive (warmup iterations may be zero)");
    params.forEach(
        (k, v) -> {
          if (v.isEmpty()) throw new IllegalArgumentException("Empty parameter: " + k);
        });
    if (modes != null) {
      if (modes.isEmpty()) throw new IllegalArgumentException("Empty modes");
      modes.forEach(RunConfig::checkMode);
    }
  }

  public static void checkMode(String mode) {
    if (!Set.of("Throughput", "AverageTime", "SingleShotTime").contains(mode))
      throw new IllegalArgumentException("Unsupported mode: " + mode);
  }
}
