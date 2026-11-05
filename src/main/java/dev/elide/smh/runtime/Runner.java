// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.graalvm.nativeimage.ImageInfo;

/** Sequential execution. Timing and compiler qualification remain separate from convergence. */
public final class Runner {
  private Runner() {}

  public static JsonArray run(List<BenchmarkSpec> specs, RunConfig config, Observer observer)
      throws Throwable {
    try {
      return run(
          specs,
          config,
          observer,
          System::nanoTime,
          "System.nanoTime",
          new ResultPublisher(System.getenv("SMH_RESULT_STREAM")));
    } catch (Throwable failure) {
      JsonObject status = new JsonObject();
      status.addProperty("status", "failed");
      status.addProperty("reason", failure.toString());
      cleanup(failure, () -> ExperimentJournal.record(config, "", Map.of(), status));
      throw failure;
    }
  }

  // Package-private seam for deterministic reference traces; production always uses nanoTime.
  static JsonArray run(
      List<BenchmarkSpec> specs, RunConfig config, Observer observer, LongSupplier clock)
      throws Throwable {
    return run(
        specs, config, observer, clock, "deterministic-test-clock", new ResultPublisher(null));
  }

  private static JsonArray run(
      List<BenchmarkSpec> specs,
      RunConfig config,
      Observer observer,
      LongSupplier clock,
      String clockName,
      ResultPublisher publisher)
      throws Throwable {
    config.validate();
    Pattern include = Pattern.compile(config.include);
    List<BenchmarkSpec> selected =
        specs.stream().filter(s -> include.matcher(s.name()).find()).toList();
    if (selected.isEmpty())
      throw new IllegalArgumentException("No benchmarks matched " + config.include);
    for (String key : config.params.keySet())
      if (selected.stream().noneMatch(s -> s.params().containsKey(key)))
        throw new IllegalArgumentException("Unknown parameter: " + key);
    if (config.mode.equals("startup"))
      return Startup.run(selected, config, "1".equals(System.getenv("SMH_STARTUP_DISCOVER")));
    JsonArray results = new JsonArray();
    for (BenchmarkSpec spec : selected) {
      Map<String, List<String>> dimensions = new TreeMap<>(spec.params());
      config.params.forEach(
          (k, v) -> {
            if (dimensions.containsKey(k)) dimensions.put(k, v);
          });
      List<Map<String, String>> combinations = new ArrayList<>();
      matrix(new ArrayList<>(dimensions.entrySet()), 0, new LinkedHashMap<>(), combinations);
      for (Map<String, String> params : combinations)
        if (config.backend == MeasurementBackend.CALLGRIND)
          results.add(publisher.publish(SimulationRunner.trial(spec, params, config, observer)));
        else
          for (String mode : config.modes == null ? spec.modes() : config.modes)
            results.add(
                publisher.publish(trial(spec, params, mode, config, observer, clock, clockName)));
    }
    if (config.output != null) {
      Files.createDirectories(config.output.toAbsolutePath().getParent());
      Files.writeString(
          config.output,
          new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(results) + "\n");
    }
    return results;
  }

  static void matrix(
      List<Map.Entry<String, List<String>>> dims,
      int i,
      Map<String, String> row,
      List<Map<String, String>> result) {
    if (i == dims.size()) {
      result.add(Map.copyOf(row));
      return;
    }
    var d = dims.get(i);
    for (String v : d.getValue()) {
      row.put(d.getKey(), v);
      matrix(dims, i + 1, row, result);
    }
  }

  private static JsonObject trial(
      BenchmarkSpec spec,
      Map<String, String> params,
      String mode,
      RunConfig c,
      Observer observer,
      LongSupplier clock,
      String clockName)
      throws Throwable {
    int warmup = c.warmupIterations == null ? spec.warmupIterations() : c.warmupIterations;
    int iterations =
        c.measurementIterations == null ? spec.measurementIterations() : c.measurementIterations;
    long wn = c.warmupNanos == null ? spec.warmupNanos() : c.warmupNanos;
    long mn = c.measurementNanos == null ? spec.measurementNanos() : c.measurementNanos;
    if (c.mode.equals("soak") && mode.equals("SingleShotTime"))
      throw new IllegalArgumentException("Soak requires AverageTime or Throughput windows");
    BenchmarkCase benchmark = Objects.requireNonNull(spec.factory().apply(params));
    if ((c.wallBatchSize > 1 || c.batchCalibration != null) && benchmark.hasInvocationFixtures())
      throw new IllegalArgumentException("Wall batches cannot contain invocation fixtures");
    Phase warm, measured;
    int batchSize = c.wallBatchSize;
    JsonObject calibration = new JsonObject();
    calibration.addProperty("status", "NOT_REQUESTED");
    benchmark.setup("Trial");
    Throwable failure = null;
    try {
      if (c.batchCalibration != null) {
        BatchCalibrator.Result selected =
            BatchCalibrator.calibrate(benchmark, clock, c.batchCalibration);
        batchSize = selected.batchSize();
        calibration = selected.report();
      }
      warm =
          phase(
              spec,
              params,
              mode,
              benchmark,
              observer,
              clock,
              false,
              warmup,
              wn,
              c.warmupStability,
              batchSize,
              0,
              c);
      measured =
          phase(
              spec,
              params,
              mode,
              benchmark,
              observer,
              clock,
              true,
              iterations,
              mn,
              c.measurementStability,
              batchSize,
              c.mode.equals("soak") ? c.durationNanos : 0,
              c);
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      cleanup(failure, () -> benchmark.tearDown("Trial"));
    }
    List<Double> samples = measured.scores;
    double mean = WindowStatistics.summarize(samples, 0, samples.size()).mean();
    String unit =
        switch (spec.timeUnit()) {
          case "NANOSECONDS" -> "ns";
          case "MICROSECONDS" -> "us";
          case "MILLISECONDS" -> "ms";
          case "SECONDS" -> "s";
          case "MINUTES" -> "min";
          case "HOURS" -> "hr";
          default -> "day";
        };
    JsonObject metric = new JsonObject();
    metric.addProperty("score", mean);
    // No confidence inference from dependent/adaptively stopped iteration observations.
    metric.add("scoreError", JsonNull.INSTANCE);
    metric.add("scoreConfidence", JsonNull.INSTANCE);
    metric.addProperty("scoreUnit", mode.equals("Throughput") ? "ops/" + unit : unit + "/op");
    JsonArray raw = new JsonArray(), fork = new JsonArray();
    samples.forEach(fork::add);
    raw.add(fork);
    metric.add("rawData", raw);
    JsonObject result = new JsonObject();
    result.addProperty("jmhVersion", "1.37");
    result.addProperty("benchmark", spec.name());
    result.addProperty(
        "mode",
        switch (mode) {
          case "Throughput" -> "thrpt";
          case "AverageTime" -> "avgt";
          default -> "ss";
        });
    result.addProperty("threads", 1);
    result.addProperty("forks", 1);
    result.addProperty("jdkVersion", System.getProperty("java.version"));
    result.addProperty("vmName", System.getProperty("java.vm.name"));
    result.addProperty("vmVersion", System.getProperty("java.vm.version"));
    result.addProperty(
        "jvm", ImageInfo.inImageRuntimeCode() ? "AOT" : System.getProperty("java.home"));
    result.add("jvmArgs", new JsonArray());
    result.addProperty("warmupIterations", warm.scores.size());
    result.addProperty("measurementIterations", samples.size());
    result.addProperty("warmupTime", wn + " ns");
    result.addProperty("measurementTime", mn + " ns");
    result.addProperty("warmupBatchSize", batchSize);
    result.addProperty("measurementBatchSize", batchSize);
    JsonObject p = new JsonObject();
    params.forEach(p::addProperty);
    result.add("params", p);
    result.add("primaryMetric", metric);
    result.add("secondaryMetrics", new JsonObject());
    JsonObject smh = new JsonObject();
    smh.addProperty("schemaVersion", 1);
    smh.addProperty("experimentMode", c.mode);
    smh.addProperty("backend", "wall");
    smh.addProperty("clock", clockName);
    smh.addProperty(
        "timingPolicy",
        batchSize == 1
            ? "per-invocation-excluding-fixtures"
            : "fixed-batch-without-invocation-fixtures");
    smh.add("batchCalibration", calibration);
    smh.addProperty("observerRegion", "iteration-including-invocation-fixtures");
    smh.addProperty("accuracy", "unqualified");
    smh.addProperty("sink", benchmark.sink());
    smh.addProperty("sinkQualification", "unverified");
    // An AOT host can embed JIT/guest runtimes. ImageInfo alone cannot establish quiescence.
    smh.addProperty("hostExecution", ImageInfo.inImageRuntimeCode() ? "aot" : "jvm");
    smh.addProperty("jitQuiescence", "unknown");
    smh.add("warmup", warm.report());
    smh.add("measurement", measured.report());
    result.add("smh", smh);
    return result;
  }

  private static Phase phase(
      BenchmarkSpec spec,
      Map<String, String> params,
      String mode,
      BenchmarkCase benchmark,
      Observer observer,
      LongSupplier clock,
      boolean measured,
      int count,
      long duration,
      StabilityPolicy policy,
      int batchSize,
      long budget,
      RunConfig config)
      throws Throwable {
    Phase phase = new Phase(policy);
    int limit = budget > 0 ? Integer.MAX_VALUE : policy == null ? count : policy.maximumSamples();
    long start = budget > 0 ? clock.getAsLong() : 0;
    long position = 0;
    phase.budget = budget;
    for (int i = 0; i < limit; i++) {
      Observation sample =
          iteration(
              spec,
              params,
              mode,
              benchmark,
              observer,
              clock,
              measured,
              i,
              budget > 0 ? Math.min(duration, budget - position) : duration,
              batchSize);
      phase.scores.add(sample.score);
      phase.elapsed.add(sample.elapsed);
      phase.operations.add(sample.operations);
      if (budget > 0) {
        phase.starts.add(position);
        position = elapsedSince(start, clock.getAsLong());
        phase.ends.add(position);
        JsonObject observation = new JsonObject();
        observation.addProperty("index", i);
        observation.addProperty("scoringMode", mode);
        observation.addProperty("elapsedNanos", sample.elapsed);
        observation.addProperty("operations", sample.operations);
        observation.addProperty("score", sample.score);
        observation.addProperty("startNanos", phase.starts.getLast());
        observation.addProperty("endNanos", position);
        ExperimentJournal.record(config, spec.name(), params, observation);
        if (position >= budget) break;
      }
      if (phase.monitor != null
          && phase.monitor.accept(sample.score).status() != StabilityMonitor.Status.COLLECTING)
        break;
    }
    if (budget > 0 && position < budget)
      throw new IllegalStateException(
          "Soak exhausted the window limit before reaching its duration");
    return phase;
  }

  private static Observation iteration(
      BenchmarkSpec spec,
      Map<String, String> params,
      String mode,
      BenchmarkCase benchmark,
      Observer observer,
      LongSupplier clock,
      boolean measured,
      int index,
      long duration,
      int batchSize)
      throws Throwable {
    benchmark.setup("Iteration");
    Throwable failure = null;
    try {
      if (measured) observer.beforeIteration(spec.name(), params, index);
      Throwable regionFailure = null;
      try {
        long elapsed = 0, operations = 0;
        long start = clock.getAsLong();
        do {
          elapsed =
              Math.addExact(
                  elapsed,
                  batchSize == 1
                      ? invocation(benchmark, clock)
                      : batch(benchmark, clock, batchSize));
          operations = Math.addExact(operations, (long) spec.operationsPerInvocation() * batchSize);
        } while (!mode.equals("SingleShotTime")
            && elapsedSince(start, clock.getAsLong()) < duration);
        double unit = TimeUnit.valueOf(spec.timeUnit()).toNanos(1);
        double score =
            mode.equals("Throughput")
                ? operations * unit / elapsed
                : (double) elapsed / operations / unit;
        // Positive checked long counts/times and the seven TimeUnit scales bound either
        // score to [1.25e-33, 7.97e32], strictly inside double's positive finite range.
        return new Observation(elapsed, operations, score);
      } catch (Throwable t) {
        regionFailure = t;
        throw t;
      } finally {
        if (measured)
          cleanup(regionFailure, () -> observer.afterIteration(spec.name(), params, index));
      }
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      cleanup(failure, () -> benchmark.tearDown("Iteration"));
    }
  }

  private static long batch(BenchmarkCase benchmark, LongSupplier clock, int count)
      throws Throwable {
    long elapsed = timedBatch(benchmark, clock, count);
    if (elapsed == 0) throw new IllegalStateException("Clock did not advance across batch");
    return elapsed;
  }

  static long timedBatch(BenchmarkCase benchmark, LongSupplier clock, int count) throws Throwable {
    long start = clock.getAsLong();
    for (int i = 0; i < count; i++) benchmark.invoke();
    return elapsedSince(start, clock.getAsLong());
  }

  private static long invocation(BenchmarkCase benchmark, LongSupplier clock) throws Throwable {
    benchmark.setup("Invocation");
    Throwable failure = null;
    try {
      long start = clock.getAsLong();
      benchmark.invoke();
      long elapsed = elapsedSince(start, clock.getAsLong());
      if (elapsed == 0) throw new IllegalStateException("Clock did not advance across invocation");
      return elapsed;
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      cleanup(failure, () -> benchmark.tearDown("Invocation"));
    }
  }

  private static long elapsedSince(long start, long end) {
    // nanoTime may wrap; subtraction is correct for intervals shorter than 2^63 ns.
    long elapsed = end - start;
    if (elapsed < 0) throw new IllegalStateException("Clock reversed or interval exceeded 2^63 ns");
    return elapsed;
  }

  @FunctionalInterface
  interface Cleanup {
    void run() throws Throwable;
  }

  static void cleanup(Throwable primary, Cleanup action) throws Throwable {
    try {
      action.run();
    } catch (Throwable t) {
      if (primary == null) throw t;
      if (primary != t) primary.addSuppressed(t);
    }
  }

  private record Observation(long elapsed, long operations, double score) {}

  private static final class Phase {
    final List<Double> scores = new ArrayList<>();
    final List<Long> elapsed = new ArrayList<>();
    final List<Long> operations = new ArrayList<>();
    final List<Long> starts = new ArrayList<>(), ends = new ArrayList<>();
    long budget;
    final StabilityPolicy policy;
    final StabilityMonitor monitor;

    Phase(StabilityPolicy policy) {
      this.policy = policy;
      monitor = policy == null ? null : new StabilityMonitor(policy);
    }

    JsonObject report() {
      JsonObject result = new JsonObject();
      JsonArray observations = new JsonArray();
      for (int i = 0; i < scores.size(); i++) {
        JsonObject sample = new JsonObject();
        sample.addProperty("index", i);
        sample.addProperty("elapsedNanos", elapsed.get(i));
        sample.addProperty("operations", operations.get(i));
        sample.addProperty("score", scores.get(i));
        if (budget > 0) {
          sample.addProperty("startNanos", starts.get(i));
          sample.addProperty("endNanos", ends.get(i));
        }
        observations.add(sample);
      }
      result.add("observations", observations);
      if (monitor == null) {
        result.addProperty("status", "not-assessed");
        result.addProperty("stoppingReason", budget > 0 ? "duration-reached" : "fixed-count");
        if (budget > 0) result.addProperty("requestedDurationNanos", budget);
      } else {
        StabilityMonitor.Result last = monitor.result();
        result.addProperty("status", last.status().name());
        result.addProperty("stoppingReason", last.reason().name());
        result.addProperty("stableWindows", last.stableWindows());
        result.addProperty("coefficientOfVariation", last.window().coefficientOfVariation());
        result.addProperty("relativeDrift", last.relativeDrift());
        result.addProperty("relativeSlope", last.window().relativeSlope());
        JsonObject bounds = new JsonObject();
        bounds.addProperty("minimumSamples", policy.minimumSamples());
        bounds.addProperty("maximumSamples", policy.maximumSamples());
        bounds.addProperty("windowSize", policy.windowSize());
        bounds.addProperty("consecutiveWindows", policy.consecutiveWindows());
        bounds.addProperty("maximumRelativeDeviation", policy.maximumRelativeDeviation());
        bounds.addProperty("maximumRelativeDrift", policy.maximumRelativeDrift());
        bounds.addProperty("maximumRelativeSlope", policy.maximumRelativeSlope());
        result.add("policy", bounds);
      }
      return result;
    }
  }
}
