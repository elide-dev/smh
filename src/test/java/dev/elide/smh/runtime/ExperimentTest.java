// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("portable")
class ExperimentTest {
  static RunConfig config(String json) {
    return RunConfig.fromJson(JsonParser.parseString(json));
  }

  static BenchmarkSpec spec(BenchmarkCase benchmark) {
    return new BenchmarkSpec(
        "work", Map.of(), List.of("AverageTime"), 0, 1, 10, 10, "NANOSECONDS", 2, p -> benchmark);
  }

  @Test
  void rejectsContradictoryBudgetsAndPolicies() {
    for (String json :
        List.of(
            "{'mode':'unknown'}",
            "{'launches':1}",
            "{'until':'exit'}",
            "{'timeoutNanos':1}",
            "{'durationNanos':1}",
            "{'operations':1}",
            "{'mode':'soak'}",
            "{'mode':'soak','durationNanos':1,'operations':1}",
            "{'mode':'soak','backend':'callgrind','invocationsPerIteration':1}",
            "{'mode':'soak','backend':'callgrind','invocationsPerIteration':1,'operations':1,'durationNanos':1}",
            "{'mode':'startup','until':'bad'}",
            "{'mode':'soak','durationNanos':1,'measurementIterations':1}"))
      assertThrows(IllegalArgumentException.class, () -> config(json), json);
    for (String key : List.of("durationNanos", "operations", "timeoutNanos", "launches"))
      assertThrows(IllegalArgumentException.class, () -> config("{'" + key + "':0}"));
    for (String policy :
        List.of(
            "'warmupIterations':0",
            "'measurementIterations':1",
            "'warmupNanos':1",
            "'measurementNanos':1",
            "'wallBatchSize':2",
            "'modes':['AverageTime']",
            "'invocationsPerIteration':1",
            "'batchCalibration':{'targetNanos':1,'maximumBatchSize':1,'maximumAttempts':1,'samplesPerAttempt':1}",
            "'warmupStability':" + stability(),
            "'measurementStability':" + stability()))
      assertThrows(
          IllegalArgumentException.class,
          () -> config("{'mode':'startup'," + policy + "}"),
          policy);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config("{'mode':'soak','durationNanos':1,'measurementStability':" + stability() + "}"));
    assertEquals("steady", config("{}").mode);
    assertEquals(
        "startup",
        config(
                "{'mode':'startup','backend':'callgrind','until':'exit','launches':2,'timeoutNanos':1}")
            .mode);
  }

  static String stability() {
    return "{'minimumSamples':4,'maximumSamples':4,'windowSize':2,'consecutiveWindows':1,"
        + "'maximumRelativeDeviation':0.1,'maximumRelativeDrift':0.1,'maximumRelativeSlope':0.1}";
  }

  @Test
  void freshLaunchLifecycleAndExplicitReadinessAreDistinct() throws Throwable {
    Startup.ready();
    for (String until : Arrays.asList(null, "ready", "first-result", "exit")) {
      List<String> events = new ArrayList<>();
      BenchmarkCase benchmark =
          new BenchmarkCase() {
            public void setup(String level) {
              events.add("setup:" + level);
              Startup.ready();
              Startup.ready();
            }

            public void invoke() {
              events.add("invoke");
            }

            public void tearDown(String level) {
              events.add("teardown:" + level);
            }
          };
      RunConfig c = config("{'mode':'startup'}");
      c.until = until;
      PrintStream previous = System.out;
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try {
        System.setOut(new PrintStream(bytes));
        assertEquals(1, Startup.run(List.of(spec(benchmark)), c, false, "token").size());
      } finally {
        System.setOut(previous);
      }
      assertEquals(
          "exit".equals(until) ? "" : "SMH_STARTUP token" + System.lineSeparator(),
          bytes.toString());
      assertEquals(
          "ready".equals(until)
              ? List.of("setup:Trial", "teardown:Trial")
              : List.of(
                  "setup:Trial",
                  "setup:Iteration",
                  "setup:Invocation",
                  "invoke",
                  "teardown:Invocation",
                  "teardown:Iteration",
                  "teardown:Trial"),
          events);
    }
  }

  @Test
  void discoveryDoesNotConstructAndFailuresUnwind() throws Throwable {
    RunConfig c = config("{'mode':'startup'}");
    BenchmarkSpec parameterized =
        new BenchmarkSpec(
            "parameterized",
            Map.of("n", List.of("1", "2")),
            List.of("AverageTime"),
            0,
            1,
            1,
            1,
            "NANOSECONDS",
            1,
            p -> {
              throw new AssertionError("discovery constructed");
            });
    assertEquals(2, Startup.run(List.of(parameterized), c, true, null).size());
    c.params.put("n", List.of("3"));
    c.params.put("other", List.of("unused"));
    assertEquals(
        "3",
        Startup.run(List.of(parameterized), c, true, null)
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("params")
            .get("n")
            .getAsString());
    assertThrows(
        IllegalArgumentException.class,
        () -> Startup.run(List.of(spec(() -> {}), spec(() -> {})), c, false, "token"));
    assertThrows(
        IllegalStateException.class, () -> Startup.run(List.of(spec(() -> {})), c, false, null));
    assertThrows(
        IllegalStateException.class,
        () -> Runner.run(List.of(spec(() -> {})), config("{'mode':'startup'}"), Observer.NONE));
    for (String failure :
        List.of(
            "setup:Trial",
            "setup:Iteration",
            "setup:Invocation",
            "invoke",
            "teardown:Invocation",
            "teardown:Iteration",
            "teardown:Trial")) {
      List<String> events = new ArrayList<>();
      RuntimeException error = new RuntimeException(failure);
      BenchmarkCase benchmark =
          new BenchmarkCase() {
            void event(String name) {
              events.add(name);
              if (name.equals(failure)) throw error;
            }

            public void setup(String level) {
              event("setup:" + level);
            }

            public void invoke() {
              event("invoke");
            }

            public void tearDown(String level) {
              event("teardown:" + level);
            }
          };
      c.until = "exit";
      assertSame(
          error,
          assertThrows(
              RuntimeException.class,
              () -> Startup.run(List.of(spec(benchmark)), c, false, "token")));
      assertEquals(
          failure.equals("setup:Trial") ? 0 : 1, Collections.frequency(events, "teardown:Trial"));
    }
    Startup.ready();
  }

  @Test
  void soakKeepsStateAndRunsWholeBudgetWithActualWindowPositions() throws Throwable {
    List<String> events = new ArrayList<>();
    BenchmarkCase benchmark =
        new BenchmarkCase() {
          public void setup(String level) {
            events.add("setup:" + level);
          }

          public void invoke() {
            events.add("invoke");
          }

          public void tearDown(String level) {
            events.add("teardown:" + level);
          }
        };
    RunConfig c = config("{'mode':'soak','durationNanos':100}");
    AtomicLong ticks = new AtomicLong();
    JsonObject row =
        Runner.run(List.of(spec(benchmark)), c, Observer.NONE, () -> ticks.getAndAdd(10))
            .get(0)
            .getAsJsonObject();
    JsonObject phase = row.getAsJsonObject("smh").getAsJsonObject("measurement");
    JsonArray samples = phase.getAsJsonArray("observations");
    assertEquals(2, samples.size());
    assertEquals(50, samples.get(0).getAsJsonObject().get("endNanos").getAsLong());
    assertEquals(100, samples.get(1).getAsJsonObject().get("endNanos").getAsLong());
    assertEquals(50, samples.get(1).getAsJsonObject().get("startNanos").getAsLong());
    assertEquals("duration-reached", phase.get("stoppingReason").getAsString());
    assertEquals(1, Collections.frequency(events, "setup:Trial"));
    assertEquals(2, Collections.frequency(events, "invoke"));
    assertEquals(5, row.getAsJsonObject("primaryMetric").get("score").getAsDouble());
    c.modes = List.of("SingleShotTime");
    assertThrows(
        IllegalArgumentException.class,
        () -> Runner.run(List.of(spec(benchmark)), c, Observer.NONE));
  }

  @Test
  void simulationSoakFinishesPartialWindowAndCountsLogicalOperations() throws Throwable {
    AtomicLong calls = new AtomicLong();
    RunConfig c =
        config("{'mode':'soak','backend':'callgrind','operations':14,'invocationsPerIteration':3}");
    JsonObject result =
        Runner.run(
                List.of(spec(calls::incrementAndGet)),
                c,
                Observer.NONE,
                () -> {
                  throw new AssertionError("clock");
                })
            .get(0)
            .getAsJsonObject();
    assertEquals(7, calls.get());
    assertEquals(JsonParser.parseString("[6,6,2]"), result.get("operationCounts"));
    assertEquals(14, result.get("requestedOperations").getAsLong());
    assertEquals("operations-reached", result.get("stoppingReason").getAsString());
    c.operations = 13L;
    assertThrows(
        IllegalArgumentException.class,
        () -> Runner.run(List.of(spec(() -> {})), c, Observer.NONE));
    c.operations = Long.MAX_VALUE - 1;
    c.invocationsPerIteration = 1L;
    assertThrows(
        ArithmeticException.class, () -> Runner.run(List.of(spec(() -> {})), c, Observer.NONE));
  }
}
