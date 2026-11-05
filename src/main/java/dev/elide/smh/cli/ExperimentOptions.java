// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import dev.elide.smh.runtime.RunConfig;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import picocli.CommandLine.Option;

/** Experiment policy is independent of average-time/throughput scoring and execution engine. */
final class ExperimentOptions {
  @Option(names = "--mode", description = "Experiment: steady, startup or soak")
  String mode;

  @Option(names = "--backend", description = "Measurement: wall or callgrind")
  String backend;

  @Option(names = "--duration", description = "Wall soak budget, e.g. 30s or 5m")
  String duration;

  @Option(
      names = "--operations",
      description = "Exact logical-operation budget for simulation soak")
  Long operations;

  @Option(names = "--launches", description = "Fresh processes per startup case (default 10)")
  Integer launches;

  @Option(names = "--until", description = "Startup endpoint: ready, first-result (default), exit")
  String until;

  @Option(names = "--timeout", description = "Startup process safety timeout (default 30s)")
  String timeout;

  @Option(
      names = "--output",
      description = "New experiment directory for configs, raw observations and logs")
  Path output;

  @Option(names = "--valgrind", defaultValue = "valgrind")
  String valgrind = "valgrind";

  static long nanos(String value) {
    var matcher =
        java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ns|us|ms|s|m|h)").matcher(value);
    if (!matcher.matches())
      throw new IllegalArgumentException("Duration needs a unit: ns, us, ms, s, m or h");
    long scale =
        switch (matcher.group(2)) {
          case "ns" -> 1L;
          case "us" -> 1_000L;
          case "ms" -> 1_000_000L;
          case "s" -> 1_000_000_000L;
          case "m" -> 60_000_000_000L;
          default -> 3_600_000_000_000L;
        };
    long result =
        new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(scale)).longValueExact();
    if (result < 1) throw new IllegalArgumentException("Duration must be positive");
    return result;
  }

  JsonObject apply(JsonObject requested) {
    JsonObject effective = requested.deepCopy();
    if (mode != null) {
      effective.addProperty("mode", mode);
      if (mode.equals("startup"))
        for (String key :
            List.of(
                "warmupIterations",
                "measurementIterations",
                "warmupNanos",
                "measurementNanos",
                "warmupStability",
                "measurementStability",
                "modes",
                "wallBatchSize",
                "batchCalibration",
                "invocationsPerIteration")) effective.remove(key);
      if (mode.equals("soak")) {
        effective.remove("measurementIterations");
        effective.remove("measurementStability");
      }
    }
    if (backend != null) {
      effective.addProperty("backend", backend);
      if (backend.equals("callgrind")) {
        for (String key :
            List.of(
                "warmupNanos",
                "measurementNanos",
                "modes",
                "wallBatchSize",
                "batchCalibration",
                "warmupStability",
                "measurementStability")) effective.remove(key);
        if (!new JsonPrimitive("startup").equals(effective.get("mode"))
            && !effective.has("invocationsPerIteration"))
          effective.addProperty("invocationsPerIteration", 10000);
      }
    }
    if (duration != null) effective.addProperty("durationNanos", nanos(duration));
    if (operations != null) effective.addProperty("operations", operations);
    if (launches != null) effective.addProperty("launches", launches);
    if (until != null) effective.addProperty("until", until);
    if (timeout != null) effective.addProperty("timeoutNanos", nanos(timeout));
    RunConfig.fromJson(effective);
    return effective;
  }

  Path directory() throws Exception {
    if (output != null) {
      Files.createDirectories(output.toAbsolutePath().getParent());
      return Files.createDirectory(output.toAbsolutePath());
    }
    return Files.createTempDirectory(
            Files.createDirectories(Path.of("build/smh-experiments")), "run-")
        .toAbsolutePath();
  }
}
