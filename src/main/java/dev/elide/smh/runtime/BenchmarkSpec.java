// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public record BenchmarkSpec(
    String name,
    Map<String, List<String>> params,
    List<String> modes,
    int warmupIterations,
    int measurementIterations,
    long warmupNanos,
    long measurementNanos,
    String timeUnit,
    int operationsPerInvocation,
    Function<Map<String, String>, BenchmarkCase> factory) {
  public BenchmarkSpec {
    if (name.isBlank()) throw new IllegalArgumentException("Benchmark name cannot be blank");
    if (warmupIterations < 0
        || measurementIterations < 1
        || warmupNanos < 1
        || measurementNanos < 1
        || operationsPerInvocation < 1)
      throw new IllegalArgumentException("Invalid benchmark counts or durations");
    TimeUnit.valueOf(timeUnit);
    modes = List.copyOf(modes);
    if (modes.isEmpty()) throw new IllegalArgumentException("Benchmark modes cannot be empty");
    modes.forEach(RunConfig::checkMode);
    Map<String, List<String>> dimensions = new TreeMap<>();
    params.forEach(
        (key, values) -> {
          if (key.isBlank() || values.isEmpty())
            throw new IllegalArgumentException("Invalid benchmark parameter: " + key);
          dimensions.put(key, List.copyOf(values));
        });
    params = Map.copyOf(dimensions);
    Objects.requireNonNull(factory);
  }
}
