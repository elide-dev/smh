// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("portable")
class BatchCalibratorTest {
  private static LongSupplier trace(long... values) {
    AtomicInteger index = new AtomicInteger();
    return () -> values[index.getAndIncrement()];
  }

  private static RunConfig config(BatchCalibrationPolicy policy) {
    RunConfig config = new RunConfig();
    config.warmupIterations = 1;
    config.measurementIterations = 1;
    config.batchCalibration = policy;
    return config;
  }

  private static BenchmarkSpec spec(BenchmarkCase benchmark) {
    return new BenchmarkSpec(
        "calibration",
        Map.of(),
        List.of("SingleShotTime"),
        1,
        1,
        1,
        1,
        "NANOSECONDS",
        1,
        p -> benchmark);
  }

  @Test
  void calibrationPrecedesWarmupKeepsAllProbesAndExcludesFixtureTime() throws Throwable {
    AtomicLong time = new AtomicLong();
    AtomicInteger invocations = new AtomicInteger();
    List<String> lifecycle = new ArrayList<>(), observed = new ArrayList<>();
    BenchmarkCase benchmark =
        new BenchmarkCase() {
          public void setup(String level) {
            lifecycle.add("setup." + level);
            time.addAndGet(1000);
          }

          public void tearDown(String level) {
            lifecycle.add("end." + level);
            time.addAndGet(1000);
          }

          public void invoke() {
            invocations.incrementAndGet();
            time.incrementAndGet();
          }
        };
    Observer observer =
        new Observer() {
          public void beforeIteration(String name, Map<String, String> params, int index) {
            observed.add("before" + index);
          }

          public void afterIteration(String name, Map<String, String> params, int index) {
            observed.add("after" + index);
          }
        };
    JsonObject result =
        Runner.run(
                List.of(spec(benchmark)),
                config(new BatchCalibrationPolicy(3, 16, 8, 2)),
                observer,
                time::get)
            .get(0)
            .getAsJsonObject();
    assertEquals(22, invocations.get()); // (1+2+4)*2 calibration + 4 warmup + 4 measured.
    assertEquals(4, result.get("warmupBatchSize").getAsInt());
    assertEquals(4, result.get("measurementBatchSize").getAsInt());
    assertEquals(1, result.getAsJsonObject("primaryMetric").get("score").getAsDouble());
    JsonObject calibration = result.getAsJsonObject("smh").getAsJsonObject("batchCalibration");
    assertEquals("TARGET_REACHED", calibration.get("status").getAsString());
    assertEquals("all-probes-met-duration", calibration.get("stoppingReason").getAsString());
    assertEquals(14, calibration.get("invocations").getAsLong());
    JsonArray attempts = calibration.getAsJsonArray("attempts");
    assertEquals(3, attempts.size());
    for (int i = 0; i < attempts.size(); i++) {
      JsonObject attempt = attempts.get(i).getAsJsonObject();
      int size = 1 << i;
      assertEquals(size, attempt.get("batchSize").getAsInt());
      assertEquals(
          JsonParser.parseString("[" + size + "," + size + "]"), attempt.get("elapsedNanos"));
    }
    assertEquals(List.of("before0", "after0"), observed);
    List<String> expected = new ArrayList<>(List.of("setup.Trial"));
    for (int i = 0; i < 8; i++) expected.addAll(List.of("setup.Iteration", "end.Iteration"));
    expected.add("end.Trial");
    assertEquals(expected, lifecycle);
  }

  @Test
  void everyProbeMustMeetTargetAndExhaustionSelectsTheLastTestedSize() throws Throwable {
    BatchCalibrator.Result result =
        BatchCalibrator.calibrate(
            () -> {}, trace(0, 1, 1, 11, 11, 17, 17, 24), new BatchCalibrationPolicy(5, 8, 4, 2));
    assertEquals(2, result.batchSize()); // One short probe prevents accepting size 1.
    for (BatchCalibrationPolicy policy :
        List.of(
            new BatchCalibrationPolicy(5, 3, 32, 1), new BatchCalibrationPolicy(5, 100, 1, 1))) {
      AtomicInteger calls = new AtomicInteger();
      result = BatchCalibrator.calibrate(calls::incrementAndGet, () -> 0, policy);
      boolean sizeBound = policy.maximumBatchSize() == 3;
      assertEquals(sizeBound ? 3 : 1, result.batchSize());
      assertEquals(sizeBound ? 6 : 1, calls.get());
      assertEquals("EXHAUSTED", result.report().get("status").getAsString());
      assertEquals(
          sizeBound ? "maximum-batch-size" : "maximum-attempts",
          result.report().get("stoppingReason").getAsString());
      for (JsonElement attempt : result.report().getAsJsonArray("attempts"))
        assertEquals(
            0, attempt.getAsJsonObject().getAsJsonArray("elapsedNanos").get(0).getAsLong());
    }
  }

  @Test
  void exhaustedCalibrationRemainsVisibleInAnOtherwiseValidMeasurement() throws Throwable {
    RunConfig settings = config(new BatchCalibrationPolicy(100, 2, 4, 1));
    settings.warmupIterations = 0;
    JsonObject result =
        Runner.run(List.of(spec(() -> {})), settings, Observer.NONE, trace(0, 1, 1, 3, 3, 3, 5))
            .get(0)
            .getAsJsonObject();
    assertEquals(2, result.get("measurementBatchSize").getAsInt());
    assertEquals(1, result.getAsJsonObject("primaryMetric").get("score").getAsDouble());
    assertEquals(
        "EXHAUSTED",
        result
            .getAsJsonObject("smh")
            .getAsJsonObject("batchCalibration")
            .get("status")
            .getAsString());
    assertEquals("unqualified", result.getAsJsonObject("smh").get("accuracy").getAsString());
  }

  @Test
  void calibrationFailuresUnwindWithoutReplacingThePrimaryFailure() {
    for (String boundary : List.of("setup", "invoke", "clock", "cleanup")) {
      RuntimeException primary = new RuntimeException(boundary);
      AssertionError cleanup = new AssertionError("cleanup");
      List<String> events = new ArrayList<>();
      BenchmarkCase benchmark =
          new BenchmarkCase() {
            public void setup(String level) {
              events.add("setup." + level);
              if (level.equals("Iteration") && boundary.equals("setup")) throw primary;
            }

            public void invoke() {
              events.add("invoke");
              if (boundary.equals("invoke")) throw primary;
            }

            public void tearDown(String level) {
              events.add("end." + level);
              if (level.equals("Iteration")) {
                if (boundary.equals("cleanup")) throw primary;
                throw cleanup;
              }
            }
          };
      LongSupplier clock =
          () -> {
            if (boundary.equals("clock")) throw primary;
            return 0;
          };
      assertSame(
          primary,
          assertThrows(
              RuntimeException.class,
              () ->
                  Runner.run(
                      List.of(spec(benchmark)),
                      config(new BatchCalibrationPolicy(5, 3, 3, 1)),
                      Observer.NONE,
                      clock)));
      assertEquals("end.Trial", events.getLast());
      assertEquals(
          boundary.equals("setup") ? 0 : 1,
          events.stream().filter(e -> e.equals("end.Iteration")).count());
      assertEquals(
          boundary.equals("invoke") || boundary.equals("clock") ? 1 : 0,
          primary.getSuppressed().length);
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            BatchCalibrator.calibrate(
                () -> {}, trace(10, 9), new BatchCalibrationPolicy(1, 1, 1, 1)));
  }

  @Test
  void policiesAndIncompatibleBackendsOrFixturesAreRejectedBeforeSetup() {
    for (long[] bounds :
        List.of(
            new long[] {0, 1, 1, 1}, new long[] {1, 0, 1, 1},
            new long[] {1, 1, 0, 1}, new long[] {1, 1, 33, 1},
            new long[] {1, 1, 1, 0}, new long[] {1, 1, 1, 65}))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new BatchCalibrationPolicy(
                  bounds[0], (int) bounds[1], (int) bounds[2], (int) bounds[3]));
    var maximum = new BatchCalibrationPolicy(Long.MAX_VALUE, Integer.MAX_VALUE, 32, 64);
    assertEquals(64, maximum.samplesPerAttempt());
    String json =
        """
        {"batchCalibration":{"targetNanos":1000,"maximumBatchSize":1024,
         "maximumAttempts":12,"samplesPerAttempt":3}}
        """;
    RunConfig parsed = RunConfig.fromJson(JsonParser.parseString(json));
    assertEquals(new BatchCalibrationPolicy(1000, 1024, 12, 3), parsed.batchCalibration);
    assertThrows(
        IllegalArgumentException.class,
        () -> RunConfig.fromJson(JsonParser.parseString("{\"batchCalibration\":{}}")));
    parsed.wallBatchSize = 2;
    assertThrows(IllegalArgumentException.class, parsed::validate);
    parsed.wallBatchSize = 1;
    parsed.backend = MeasurementBackend.CALLGRIND;
    assertThrows(IllegalArgumentException.class, parsed::validate);
    BenchmarkCase fixtures =
        new BenchmarkCase() {
          public boolean hasInvocationFixtures() {
            return true;
          }

          public void setup(String level) {
            fail("Must reject calibration before setup");
          }

          public void invoke() {
            fail("Must reject calibration before invoking");
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> Runner.run(List.of(spec(fixtures)), config(maximum), Observer.NONE, () -> 0));
  }
}
