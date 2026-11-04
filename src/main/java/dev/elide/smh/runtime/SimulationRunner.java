// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.*;
import java.util.Map;
import java.util.Objects;

/** Executes fixed work. Only an external simulator importer can turn this manifest into counts. */
public final class SimulationRunner {
  private SimulationRunner() {}

  /** The non-inlined, symbol-preserved boundary selected by Callgrind. Contains no clock reads. */
  public static void measureBatch(BenchmarkCase benchmark, long invocations) throws Throwable {
    for (long i = 0; i < invocations; i++) benchmark.invoke();
  }

  static JsonObject trial(
      BenchmarkSpec spec, Map<String, String> params, RunConfig config, Observer observer)
      throws Throwable {
    long invocations = config.invocationsPerIteration;
    long operations = Math.multiplyExact(invocations, spec.operationsPerInvocation());
    int warmups =
        config.warmupIterations == null ? spec.warmupIterations() : config.warmupIterations;
    int measurements =
        config.measurementIterations == null
            ? spec.measurementIterations()
            : config.measurementIterations;
    BenchmarkCase benchmark = Objects.requireNonNull(spec.factory().apply(params));
    if (benchmark.hasInvocationFixtures())
      throw new IllegalArgumentException(
          "Simulation batch boundaries cannot exclude invocation fixtures: " + spec.name());
    benchmark.setup("Trial");
    Throwable failure = null;
    try {
      for (int i = 0; i < warmups; i++)
        iteration(benchmark, invocations, false, observer, spec.name(), params, i);
      for (int i = 0; i < measurements; i++)
        iteration(benchmark, invocations, true, observer, spec.name(), params, i);
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      cleanup(failure, () -> benchmark.tearDown("Trial"));
    }
    JsonObject result = new JsonObject();
    result.addProperty("schemaVersion", 1);
    result.addProperty("benchmark", spec.name());
    result.addProperty("backend", "callgrind");
    result.addProperty("quantity", "simulated-events");
    result.addProperty("status", "pending-counter-import");
    result.addProperty("accuracy", "unqualified");
    result.addProperty("sink", benchmark.sink());
    result.addProperty("sinkQualification", "unverified");
    result.addProperty("region", "benchmark-thread-inclusive-batch-with-loop-and-call-overhead");
    result.addProperty("warmupIterations", warmups);
    result.addProperty("measurementIterations", measurements);
    result.addProperty("invocationsPerIteration", invocations);
    result.addProperty("operationsPerIteration", operations);
    JsonObject parameters = new JsonObject();
    params.forEach(parameters::addProperty);
    result.add("params", parameters);
    return result;
  }

  private static void iteration(
      BenchmarkCase benchmark,
      long count,
      boolean measured,
      Observer observer,
      String name,
      Map<String, String> params,
      int index)
      throws Throwable {
    benchmark.setup("Iteration");
    Throwable failure = null;
    try {
      if (measured) observer.beforeIteration(name, params, index);
      Throwable regionFailure = null;
      try {
        if (measured) measureBatch(benchmark, count);
        else for (long i = 0; i < count; i++) benchmark.invoke();
      } catch (Throwable t) {
        regionFailure = t;
        throw t;
      } finally {
        if (measured) cleanup(regionFailure, () -> observer.afterIteration(name, params, index));
      }
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      cleanup(failure, () -> benchmark.tearDown("Iteration"));
    }
  }

  @FunctionalInterface
  private interface Cleanup {
    void run() throws Throwable;
  }

  private static void cleanup(Throwable primary, Cleanup action) throws Throwable {
    try {
      action.run();
    } catch (Throwable failure) {
      if (primary == null) throw failure;
      if (primary != failure) primary.addSuppressed(failure);
    }
  }
}
