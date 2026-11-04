// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class RunnerTest {
  @TempDir Path directory;

  private static BenchmarkSpec spec(
      String name,
      Map<String, List<String>> params,
      List<String> modes,
      String unit,
      int operations,
      BenchmarkCase benchmark) {
    return new BenchmarkSpec(name, params, modes, 1, 2, 1, 1, unit, operations, p -> benchmark);
  }

  private static BenchmarkSpec single(BenchmarkCase benchmark) {
    return spec("Example.work", Map.of(), List.of("SingleShotTime"), "NANOSECONDS", 1, benchmark);
  }

  private static RunConfig one() {
    RunConfig config = new RunConfig();
    config.warmupIterations = 0;
    config.measurementIterations = 1;
    return config;
  }

  private static LongSupplier ticks(long increment) {
    return new LongSupplier() {
      long next;

      public long getAsLong() {
        long value = next;
        next += increment;
        return value;
      }
    };
  }

  private static LongSupplier trace(long... values) {
    return new LongSupplier() {
      int index;

      public long getAsLong() {
        assertTrue(index < values.length, "Unexpected clock read");
        return values[index++];
      }
    };
  }

  private static JsonObject run(BenchmarkSpec spec, RunConfig config, LongSupplier clock)
      throws Throwable {
    return Runner.run(List.of(spec), config, Observer.NONE, clock).get(0).getAsJsonObject();
  }

  private static JsonObject metric(JsonObject result) {
    return result.getAsJsonObject("primaryMetric");
  }

  @Test
  void fixedBatchesUseOneClockPairAndNormalizeAllOperations() throws Throwable {
    for (String mode : List.of("AverageTime", "Throughput", "SingleShotTime")) {
      List<String> events = new ArrayList<>();
      RunConfig config = one();
      config.wallBatchSize = 3;
      config.measurementNanos = 100L;
      JsonObject result =
          run(
              spec("batch", Map.of(), List.of(mode), "NANOSECONDS", 2, recording(events, Map.of())),
              config,
              mode.equals("SingleShotTime") ? trace(0, 10, 40) : trace(0, 10, 40, 50, 60, 90, 100));
      assertEquals(
          mode.equals("Throughput") ? 0.2 : 5.0, metric(result).get("score").getAsDouble());
      assertEquals(
          mode.equals("SingleShotTime") ? 3 : 6,
          events.stream().filter(e -> e.equals("invoke")).count());
      assertFalse(events.stream().anyMatch(e -> e.contains("Invocation")));
      assertEquals(3, result.get("measurementBatchSize").getAsInt());
      assertEquals(3, result.get("warmupBatchSize").getAsInt());
      assertEquals(
          "fixed-batch-without-invocation-fixtures",
          result.getAsJsonObject("smh").get("timingPolicy").getAsString());
    }
  }

  @Test
  void batchesRejectFixturesAndInvalidTimersAndDoNotPublishPartialWork() throws Throwable {
    RunConfig config = one();
    config.wallBatchSize = 2;
    BenchmarkCase fixtures =
        new BenchmarkCase() {
          public boolean hasInvocationFixtures() {
            return true;
          }

          public void setup(String level) {
            fail("Rejection must precede setup");
          }

          public void invoke() {
            fail("Rejection must precede invocation");
          }
        };
    assertThrows(IllegalArgumentException.class, () -> run(single(fixtures), config, trace()));
    for (LongSupplier clock : List.of(trace(0, 10, 10), trace(0, 10, 9)))
      assertThrows(IllegalStateException.class, () -> run(single(() -> {}), config, clock));
    List<String> events = new ArrayList<>();
    RuntimeException failure = new RuntimeException("batch failure");
    assertSame(
        failure,
        assertThrows(
            RuntimeException.class,
            () -> run(single(recording(events, Map.of("invoke", failure))), config, trace(0, 10))));
    assertEquals(
        List.of("setup.Trial", "setup.Iteration", "invoke", "tearDown.Iteration", "tearDown.Trial"),
        events);
    Path invalid = Files.writeString(directory.resolve("batch.json"), "{\"wallBatchSize\":0}");
    assertThrows(IllegalArgumentException.class, () -> RunConfig.read(invalid.toString()));
    Files.writeString(invalid, "{\"wallBatchSize\":2}");
    assertEquals(2, RunConfig.read(invalid.toString()).wallBatchSize);
    Files.writeString(
        invalid, "{\"backend\":\"callgrind\",\"invocationsPerIteration\":3,\"wallBatchSize\":2}");
    assertThrows(IllegalArgumentException.class, () -> RunConfig.read(invalid.toString()));
  }

  @Test
  void independentReferenceTracesExcludeFixturesAndNormalizeActualOperations() throws Throwable {
    for (String mode : List.of("AverageTime", "Throughput")) {
      List<String> events = new ArrayList<>();
      BenchmarkCase benchmark = recording(events, Map.of());
      RunConfig config = one();
      config.measurementNanos = 100_000L;
      JsonObject result =
          run(
              spec("trace", Map.of(), List.of(mode), "NANOSECONDS", 2, benchmark),
              config,
              trace(0, 1_000, 1_010, 50_000, 51_000, 51_030, 100_000));
      // Two invocations contribute 10 + 30 ns, each representing two operations.
      assertEquals(mode.equals("Throughput") ? 0.1 : 10, metric(result).get("score").getAsDouble());
      JsonObject observation =
          result
              .getAsJsonObject("smh")
              .getAsJsonObject("measurement")
              .getAsJsonArray("observations")
              .get(0)
              .getAsJsonObject();
      assertEquals(40, observation.get("elapsedNanos").getAsLong());
      assertEquals(4, observation.get("operations").getAsLong());
      assertEquals(2, Collections.frequency(events, "invoke"));
      assertEquals(mode.equals("Throughput") ? "thrpt" : "avgt", result.get("mode").getAsString());
    }
  }

  @Test
  void allTimeUnitsAndModesHaveAnalyticallyKnownScores() throws Throwable {
    String[] units = {
      "NANOSECONDS", "MICROSECONDS", "MILLISECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS"
    };
    String[] symbols = {"ns", "us", "ms", "s", "min", "hr", "day"};
    long[] nanos = {
      1, 1_000, 1_000_000, 1_000_000_000, 60_000_000_000L, 3_600_000_000_000L, 86_400_000_000_000L
    };
    for (int i = 0; i < units.length; i++) {
      for (String mode : List.of("SingleShotTime", "AverageTime", "Throughput")) {
        JsonObject result =
            run(spec("unit", Map.of(), List.of(mode), units[i], 4, () -> {}), one(), ticks(100));
        double expected = mode.equals("Throughput") ? nanos[i] / 25.0 : 25.0 / nanos[i];
        assertEquals(expected, metric(result).get("score").getAsDouble(), Math.ulp(expected));
        assertEquals(
            mode.equals("Throughput") ? "ops/" + symbols[i] : symbols[i] + "/op",
            metric(result).get("scoreUnit").getAsString());
        assertTrue(metric(result).get("scoreConfidence").isJsonNull());
        assertTrue(metric(result).get("scoreError").isJsonNull());
      }
    }
  }

  @Test
  void validatedIntegerExtremesProduceFinitePositiveScoresWithoutClamping() throws Throwable {
    for (long elapsed : List.of(1L, Long.MAX_VALUE))
      for (int operations : List.of(1, Integer.MAX_VALUE))
        for (String unit : List.of("NANOSECONDS", "DAYS"))
          for (String mode : List.of("SingleShotTime", "Throughput")) {
            JsonObject result =
                run(
                    spec("extreme", Map.of(), List.of(mode), unit, operations, () -> {}),
                    one(),
                    trace(0, 0, elapsed, elapsed));
            double score = metric(result).get("score").getAsDouble();
            java.math.BigDecimal nanos =
                java.math.BigDecimal.valueOf(unit.equals("DAYS") ? 86_400_000_000_000L : 1);
            java.math.BigDecimal work = java.math.BigDecimal.valueOf(operations);
            java.math.BigDecimal time = java.math.BigDecimal.valueOf(elapsed);
            java.math.MathContext precision = java.math.MathContext.DECIMAL128;
            double expected =
                mode.equals("Throughput")
                    ? work.multiply(nanos).divide(time, precision).doubleValue()
                    : time.divide(work.multiply(nanos), precision).doubleValue();
            assertTrue(Double.isFinite(score));
            assertTrue(score > 0);
            assertEquals(expected, score, 2 * Math.ulp(expected));
          }
  }

  @Test
  void warmupAndMeasurementLifecycleIsBalancedAndRawHistoriesAreComplete() throws Throwable {
    List<String> events = new ArrayList<>();
    Observer observer = observer(events, Map.of());
    JsonObject result =
        Runner.run(
                List.of(single(recording(events, Map.of()))), new RunConfig(), observer, ticks(10))
            .get(0)
            .getAsJsonObject();
    assertEquals(
        List.of(
            "setup.Trial",
            "setup.Iteration",
            "setup.Invocation",
            "invoke",
            "tearDown.Invocation",
            "tearDown.Iteration",
            "setup.Iteration",
            "before",
            "setup.Invocation",
            "invoke",
            "tearDown.Invocation",
            "after",
            "tearDown.Iteration",
            "setup.Iteration",
            "before",
            "setup.Invocation",
            "invoke",
            "tearDown.Invocation",
            "after",
            "tearDown.Iteration",
            "tearDown.Trial"),
        events);
    JsonObject smh = result.getAsJsonObject("smh");
    assertEquals("unqualified", smh.get("accuracy").getAsString());
    assertEquals("unknown", smh.get("jitQuiescence").getAsString());
    assertEquals("jvm", smh.get("hostExecution").getAsString());
    assertEquals("not-assessed", smh.getAsJsonObject("measurement").get("status").getAsString());
    assertEquals(1, smh.getAsJsonObject("warmup").getAsJsonArray("observations").size());
    assertEquals(2, smh.getAsJsonObject("measurement").getAsJsonArray("observations").size());
    assertEquals(2, metric(result).getAsJsonArray("rawData").get(0).getAsJsonArray().size());
  }

  @Test
  void failuresAtEveryLifecycleBoundaryOnlyUnwindSuccessfullyEnteredScopes() {
    Map<String, List<String>> tails =
        Map.of(
            "setup.Trial", List.of(),
            "setup.Iteration", List.of("tearDown.Trial"),
            "before", List.of("tearDown.Iteration", "tearDown.Trial"),
            "setup.Invocation", List.of("after", "tearDown.Iteration", "tearDown.Trial"),
            "invoke",
                List.of("tearDown.Invocation", "after", "tearDown.Iteration", "tearDown.Trial"),
            "tearDown.Invocation", List.of("after", "tearDown.Iteration", "tearDown.Trial"),
            "after", List.of("tearDown.Iteration", "tearDown.Trial"),
            "tearDown.Iteration", List.of("tearDown.Trial"),
            "tearDown.Trial", List.of());
    tails.forEach(
        (point, tail) -> {
          List<String> events = new ArrayList<>();
          AssertionError original = new AssertionError(point);
          Map<String, Throwable> failures = Map.of(point, original);
          Throwable actual =
              assertThrows(
                  Throwable.class,
                  () ->
                      Runner.run(
                          List.of(single(recording(events, failures))),
                          one(),
                          observer(events, failures),
                          ticks(10)));
          assertSame(original, actual);
          assertEquals(tail, events.subList(events.indexOf(point) + 1, events.size()), point);
        });
  }

  @Test
  void cleanupFailuresAreSuppressedInUnwindOrderWithoutReplacingThePrimary() {
    List<String> events = new ArrayList<>();
    AssertionError primary = new AssertionError("work");
    Throwable invocation = new IllegalStateException("invocation");
    Throwable after = new IllegalStateException("after");
    Throwable iteration = new IllegalStateException("iteration");
    Throwable trial = new IllegalStateException("trial");
    Map<String, Throwable> failures =
        Map.of(
            "invoke",
            primary,
            "tearDown.Invocation",
            invocation,
            "after",
            after,
            "tearDown.Iteration",
            iteration,
            "tearDown.Trial",
            trial);
    assertSame(
        primary,
        assertThrows(
            AssertionError.class,
            () ->
                Runner.run(
                    List.of(single(recording(events, failures))),
                    one(),
                    observer(events, failures),
                    ticks(10))));
    assertArrayEquals(
        new Throwable[] {invocation, after, iteration, trial}, primary.getSuppressed());

    // Reusing the primary throwable during cleanup must not trigger Java's self-suppression error.
    AssertionError reused = new AssertionError("reused");
    Map<String, Throwable> same = Map.of("invoke", reused, "tearDown.Invocation", reused);
    assertSame(
        reused,
        assertThrows(
            AssertionError.class, () -> run(single(recording(events, same)), one(), ticks(10))));
    assertEquals(0, reused.getSuppressed().length);
  }

  @Test
  void clockFailuresUnwindBordersAndNeverPublishPartialResults() throws Exception {
    for (int failingRead : List.of(0, 1, 2, 3)) {
      List<String> events = new ArrayList<>();
      AssertionError failure = new AssertionError("clock");
      final int at = failingRead;
      LongSupplier clock =
          new LongSupplier() {
            int read;

            public long getAsLong() {
              if (read++ == at) throw failure;
              return read * 10L;
            }
          };
      RunConfig config = one();
      config.modes = List.of("AverageTime");
      config.output = directory.resolve("failed.json");
      assertSame(
          failure,
          assertThrows(
              AssertionError.class,
              () ->
                  Runner.run(
                      List.of(single(recording(events, Map.of()))),
                      config,
                      observer(events, Map.of()),
                      clock)));
      assertEquals(1, Collections.frequency(events, "after"));
      assertEquals("tearDown.Trial", events.getLast());
      assertFalse(Files.exists(config.output));
    }
  }

  @Test
  void invalidClockTracesFailAndNanoTimeWrapIsHandled() throws Throwable {
    for (long[] invalid : List.of(new long[] {0, 100, 100}, new long[] {0, 100, 99}))
      assertThrows(IllegalStateException.class, () -> run(single(() -> {}), one(), trace(invalid)));
    RunConfig looping = one();
    looping.modes = List.of("AverageTime");
    assertThrows(
        IllegalStateException.class,
        () -> run(single(() -> {}), looping, trace(100, 110, 120, 99)));
    JsonObject result =
        run(
            single(() -> {}),
            one(),
            trace(Long.MAX_VALUE - 20, Long.MAX_VALUE - 5, Long.MIN_VALUE + 4));
    assertEquals(10, metric(result).get("score").getAsDouble());
    assertThrows(
        ArithmeticException.class,
        () -> run(single(() -> {}), looping, trace(0, 0, Long.MAX_VALUE - 1, 0, 0, 100)));
  }

  @Test
  void convergedWarmupAndMeasurementStopWithinPredeclaredBounds() throws Throwable {
    RunConfig config = new RunConfig();
    StabilityPolicy policy = new StabilityPolicy(4, 9, 2, 2, 0, 0, 0);
    config.warmupStability = policy;
    config.measurementStability = policy;
    List<String> events = new ArrayList<>();
    JsonObject result =
        Runner.run(
                List.of(single(recording(events, Map.of()))),
                config,
                observer(events, Map.of()),
                ticks(10))
            .get(0)
            .getAsJsonObject();
    assertEquals(5, result.get("warmupIterations").getAsInt());
    assertEquals(5, result.get("measurementIterations").getAsInt());
    assertEquals(5, Collections.frequency(events, "before"));
    assertEquals(10, Collections.frequency(events, "invoke"));
    for (String name : List.of("warmup", "measurement")) {
      JsonObject phase = result.getAsJsonObject("smh").getAsJsonObject(name);
      assertEquals("STABLE", phase.get("status").getAsString());
      assertEquals("CONVERGED", phase.get("stoppingReason").getAsString());
      assertEquals(2, phase.get("stableWindows").getAsInt());
      assertEquals(5, phase.getAsJsonArray("observations").size());
      assertEquals(9, phase.getAsJsonObject("policy").get("maximumSamples").getAsInt());
    }
    assertEquals("unqualified", result.getAsJsonObject("smh").get("accuracy").getAsString());
  }

  @Test
  void unstableSamplesStopAtTheLimitAndRetainEveryObservation() throws Throwable {
    RunConfig config = new RunConfig();
    config.warmupIterations = 0;
    config.measurementStability = new StabilityPolicy(4, 6, 2, 1, 0, 0, 0);
    JsonObject result =
        run(
            single(() -> {}),
            config,
            trace(0, 0, 10, 20, 20, 40, 50, 50, 60, 70, 70, 90, 100, 100, 110, 120, 120, 140));
    JsonObject phase = result.getAsJsonObject("smh").getAsJsonObject("measurement");
    assertEquals("LIMIT_REACHED", phase.get("status").getAsString());
    assertEquals("SAMPLE_LIMIT", phase.get("stoppingReason").getAsString());
    JsonArray samples = phase.getAsJsonArray("observations");
    assertEquals(6, samples.size());
    for (int i = 0; i < samples.size(); i++) {
      assertEquals(i, samples.get(i).getAsJsonObject().get("index").getAsInt());
      assertEquals(
          i % 2 == 0 ? 10 : 20, samples.get(i).getAsJsonObject().get("score").getAsDouble());
    }
    assertEquals(15, metric(result).get("score").getAsDouble());
  }

  @Test
  void selectionMatrixOverridesFilesAndFreshStateWorkTogether() throws Throwable {
    List<Map<String, String>> created = new ArrayList<>();
    BenchmarkSpec a =
        new BenchmarkSpec(
            "A",
            Map.of("size", List.of("1", "2"), "kind", List.of("x", "y")),
            List.of("SingleShotTime"),
            0,
            1,
            1,
            1,
            "NANOSECONDS",
            1,
            p -> {
              created.add(p);
              return () -> {};
            });
    BenchmarkSpec b = single(() -> {});
    RunConfig config = one();
    config.params.put("size", List.of("9"));
    config.modes = List.of("AverageTime", "Throughput");
    config.output = directory.resolve("nested/result.json");
    JsonArray result = Runner.run(List.of(a, b), config, Observer.NONE, ticks(10));
    assertEquals(6, result.size());
    assertEquals(4, created.size());
    assertEquals(
        Set.of(Map.of("size", "9", "kind", "x"), Map.of("size", "9", "kind", "y")),
        Set.copyOf(created));
    assertEquals(result, JsonParser.parseString(Files.readString(config.output)));
    config.include = "^A$";
    assertEquals(4, Runner.run(List.of(a, b), config, Observer.NONE, ticks(10)).size());
    config.params.put("unknown", List.of("value"));
    assertThrows(
        IllegalArgumentException.class, () -> Runner.run(List.of(a), config, Observer.NONE));
    config.params.clear();
    config.include = "absent";
    assertThrows(
        IllegalArgumentException.class, () -> Runner.run(List.of(a), config, Observer.NONE));
    assertThrows(IllegalArgumentException.class, () -> Runner.run(List.of(), one(), Observer.NONE));
    assertEquals(
        "System.nanoTime",
        Runner.run(List.of(b), one(), Observer.NONE)
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("smh")
            .get("clock")
            .getAsString());
  }

  private static BenchmarkCase recording(List<String> events, Map<String, Throwable> failures) {
    return new BenchmarkCase() {
      public void setup(String level) throws Throwable {
        event("setup." + level, events, failures);
      }

      public void tearDown(String level) throws Throwable {
        event("tearDown." + level, events, failures);
      }

      public void invoke() throws Throwable {
        event("invoke", events, failures);
      }
    };
  }

  private static Observer observer(List<String> events, Map<String, Throwable> failures) {
    return new Observer() {
      private void action(String name) {
        try {
          event(name, events, failures);
        } catch (Error e) {
          throw e;
        } catch (Throwable t) {
          throw (RuntimeException) t;
        }
      }

      public void beforeIteration(String name, Map<String, String> params, int index) {
        action("before");
      }

      public void afterIteration(String name, Map<String, String> params, int index) {
        action("after");
      }
    };
  }

  private static void event(String name, List<String> events, Map<String, Throwable> failures)
      throws Throwable {
    events.add(name);
    if (failures.containsKey(name)) throw failures.get(name);
  }
}
