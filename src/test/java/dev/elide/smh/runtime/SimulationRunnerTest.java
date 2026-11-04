// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class SimulationRunnerTest {
  @TempDir Path directory;

  private static RunConfig config() {
    RunConfig c = new RunConfig();
    c.backend = MeasurementBackend.CALLGRIND;
    c.invocationsPerIteration = 3L;
    return c;
  }

  private static BenchmarkSpec spec(BenchmarkCase benchmark, int operations) {
    return new BenchmarkSpec(
        "work",
        Map.of("size", List.of("4", "8")),
        List.of("AverageTime", "Throughput"),
        1,
        2,
        1,
        1,
        "NANOSECONDS",
        operations,
        p -> benchmark);
  }

  @Test
  void exactBudgetsDoNotReadClocksOrDuplicateTheJmhModeMatrix() throws Throwable {
    List<String> events = new ArrayList<>();
    BenchmarkCase benchmark =
        new BenchmarkCase() {
          public void setup(String level) {
            events.add("setup:" + level);
          }

          public void tearDown(String level) {
            events.add("teardown:" + level);
          }

          public void invoke() {
            events.add("invoke");
          }
        };
    Observer observer =
        new Observer() {
          public void beforeIteration(String n, Map<String, String> p, int i) {
            events.add("begin:" + i);
          }

          public void afterIteration(String n, Map<String, String> p, int i) {
            events.add("end:" + i);
          }
        };
    RunConfig config = config();
    config.params.put("size", List.of("16"));
    JsonArray results =
        Runner.run(
            List.of(spec(benchmark, 7)),
            config,
            observer,
            () -> {
              throw new AssertionError("Simulation must never read the wall clock");
            });
    assertEquals(1, results.size());
    JsonObject result = results.get(0).getAsJsonObject();
    assertEquals("pending-counter-import", result.get("status").getAsString());
    assertFalse(result.has("primaryMetric"));
    assertEquals(21, result.get("operationsPerIteration").getAsLong());
    assertEquals("16", result.getAsJsonObject("params").get("size").getAsString());
    assertEquals(
        List.of(
            "setup:Trial",
            "setup:Iteration",
            "invoke",
            "invoke",
            "invoke",
            "teardown:Iteration",
            "setup:Iteration",
            "begin:0",
            "invoke",
            "invoke",
            "invoke",
            "end:0",
            "teardown:Iteration",
            "setup:Iteration",
            "begin:1",
            "invoke",
            "invoke",
            "invoke",
            "end:1",
            "teardown:Iteration",
            "teardown:Trial"),
        events);
    config.warmupIterations = 0;
    config.measurementIterations = 1;
    events.clear();
    Runner.run(List.of(spec(benchmark, 1)), config, Observer.NONE);
    assertEquals(3, Collections.frequency(events, "invoke"));
  }

  @Test
  void unsupportedFixturesAndOverflowFailBeforeEnteringTrial() {
    BenchmarkCase fixture =
        new BenchmarkCase() {
          public boolean hasInvocationFixtures() {
            return true;
          }

          public void setup(String level) {
            fail("Must reject before setup");
          }

          public void invoke() {
            fail("Must not invoke");
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> Runner.run(List.of(spec(fixture, 1)), config(), Observer.NONE));
    RunConfig c = config();
    c.invocationsPerIteration = Long.MAX_VALUE;
    assertThrows(
        ArithmeticException.class, () -> Runner.run(List.of(spec(fixture, 2)), c, Observer.NONE));
  }

  @Test
  void enteredScopesUnwindAndPrimaryFailuresSurviveEveryBoundary() {
    for (String location :
        List.of(
            "setup:Trial",
            "setup:Iteration",
            "before",
            "invoke",
            "after",
            "teardown:Iteration",
            "teardown:Trial",
            "warmup")) {
      List<String> events = new ArrayList<>();
      RuntimeException primary = new RuntimeException(location);
      BenchmarkCase benchmark =
          new BenchmarkCase() {
            void event(String name) {
              events.add(name);
              if (location.equals(name) || location.equals("warmup") && name.equals("invoke"))
                throw primary;
            }

            public void setup(String level) {
              event("setup:" + level);
            }

            public void tearDown(String level) {
              event("teardown:" + level);
            }

            public void invoke() {
              event("invoke");
            }
          };
      Observer observer =
          new Observer() {
            public void beforeIteration(String n, Map<String, String> p, int i) {
              events.add("before");
              if (location.equals("before")) throw primary;
            }

            public void afterIteration(String n, Map<String, String> p, int i) {
              events.add("after");
              if (location.equals("after")) throw primary;
            }
          };
      RunConfig c = config();
      c.warmupIterations = location.equals("warmup") ? 1 : 0;
      c.measurementIterations = 1;
      assertSame(
          primary,
          assertThrows(
              RuntimeException.class, () -> Runner.run(List.of(spec(benchmark, 1)), c, observer)));
      assertEquals(
          location.equals("setup:Trial") ? 0 : 1, Collections.frequency(events, "teardown:Trial"));
      if (Set.of("setup:Trial", "setup:Iteration", "before", "warmup").contains(location))
        assertFalse(events.contains("after"));
      else assertEquals(1, Collections.frequency(events, "after"));
    }
    for (boolean same : List.of(false, true)) {
      RuntimeException primary = new RuntimeException("invoke"),
          cleanup = same ? primary : new RuntimeException("cleanup");
      BenchmarkCase benchmark =
          new BenchmarkCase() {
            public void invoke() {
              throw primary;
            }

            public void tearDown(String level) {
              throw cleanup;
            }
          };
      RunConfig c = config();
      c.warmupIterations = 0;
      assertSame(
          primary,
          assertThrows(
              RuntimeException.class,
              () -> Runner.run(List.of(spec(benchmark, 1)), c, Observer.NONE)));
      assertEquals(same ? 0 : 2, primary.getSuppressed().length);
    }
  }

  @Test
  void strictConfigurationDistinguishesTimeFromWorkAndRejectsUnsupportedConvergence()
      throws Exception {
    Path file = directory.resolve("run.json");
    Files.writeString(file, "{\"backend\":\"callgrind\",\"invocationsPerIteration\":17}");
    assertEquals(17, RunConfig.read(file.toString()).invocationsPerIteration);
    Files.writeString(file, "{\"backend\":\"wall\"}");
    assertEquals(MeasurementBackend.WALL, RunConfig.read(file.toString()).backend);
    assertThrows(IllegalArgumentException.class, () -> MeasurementBackend.parse("codspeed"));
    for (Long count : Arrays.asList(null, 0L, -1L)) {
      RunConfig c = config();
      c.invocationsPerIteration = count;
      assertThrows(IllegalArgumentException.class, c::validate);
    }
    RunConfig wall = new RunConfig();
    wall.invocationsPerIteration = 1L;
    assertThrows(IllegalArgumentException.class, wall::validate);
    StabilityPolicy policy = new StabilityPolicy(4, 4, 2, 1, 0.1, 0.1, 0.1);
    for (int i = 0; i < 5; i++) {
      RunConfig c = config();
      switch (i) {
        case 0 -> c.warmupNanos = 1L;
        case 1 -> c.measurementNanos = 1L;
        case 2 -> c.modes = List.of("AverageTime");
        case 3 -> c.warmupStability = policy;
        default -> c.measurementStability = policy;
      }
      assertThrows(IllegalArgumentException.class, c::validate);
    }
  }
}
