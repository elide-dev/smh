// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh;

import com.google.gson.*;
import dev.elide.smh.fixtures.Workflow;
import dev.elide.smh.generated.Benchmarks;
import dev.elide.smh.runtime.*;
import dev.elide.smh.runtime.Observer;
import java.nio.file.*;
import java.util.*;

/** Tests observable behavior of generated adapters and the runner together. */
public final class HarnessTest {
  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Throwable {
    int[] hooks = new int[2];
    Observer observer =
        new Observer() {
          @Override
          public void beforeIteration(String b, Map<String, String> p, int iteration) {
            hooks[0]++;
          }

          @Override
          public void afterIteration(String b, Map<String, String> p, int iteration) {
            hooks[1]++;
          }
        };
    RunConfig config = new RunConfig();
    config.output = Path.of(args[0]);
    JsonArray results = Runner.run(Benchmarks.all(), config, observer);
    check(results.size() == 8, "Parameter Cartesian product including enum defaults");
    check(Workflow.trials == 8 && Workflow.trialEnds == 8, "Fresh state per trial");
    check(Workflow.iterations == 40 && Workflow.iterationEnds == 40, "Iteration lifecycle");
    check(Workflow.invocations == 40 && Workflow.invocationEnds == 40, "Invocation lifecycle");
    check(hooks[0] == 24 && hooks[1] == 24, "Observers bracket measurement only");
    check(
        JsonParser.parseString(Files.readString(config.output)).equals(results),
        "Result file agrees with stdout data");
    for (JsonElement element : results) {
      var result = element.getAsJsonObject();
      var metric = result.getAsJsonObject("primaryMetric");
      check(result.get("mode").getAsString().equals("ss"), "Mode mapping");
      check(metric.get("scoreUnit").getAsString().equals("ns/op"), "Unit conversion");
      check(
          metric.getAsJsonArray("rawData").get(0).getAsJsonArray().size() == 3,
          "Raw iteration samples");
      check(Double.isFinite(metric.get("score").getAsDouble()), "Finite score");
    }
    config.params.put("size", List.of("7"));
    config.warmupIterations = 0;
    config.measurementIterations = 1;
    check(Runner.run(Benchmarks.all(), config, Observer.NONE).size() == 4, "Run overrides");
    config.include = "does-not-exist";
    try {
      Runner.run(Benchmarks.all(), config, Observer.NONE);
      throw new AssertionError("Missing selection should fail");
    } catch (IllegalArgumentException expected) {
    }
    int[] cleanup = new int[4];
    BenchmarkSpec failure =
        new BenchmarkSpec(
            "failure",
            Map.of(),
            List.of("SingleShotTime"),
            0,
            1,
            1,
            1,
            "SECONDS",
            1,
            p ->
                new BenchmarkCase() {
                  @Override
                  public void invoke() {
                    throw new IllegalStateException("benchmark failed");
                  }

                  @Override
                  public void tearDown(String level) {
                    cleanup[
                        switch (level) {
                          case "Invocation" -> 0;
                          case "Iteration" -> 1;
                          default -> 2;
                        }]++;
                  }
                });
    try {
      Runner.run(
          List.of(failure),
          new RunConfig(),
          new Observer() {
            @Override
            public void afterIteration(String b, Map<String, String> p, int i) {
              cleanup[3]++;
            }
          });
      throw new AssertionError("Failure must propagate");
    } catch (IllegalStateException expected) {
      check(expected.getMessage().equals("benchmark failed"), "Original exception");
    }
    check(
        Arrays.equals(cleanup, new int[] {1, 1, 1, 1}),
        "Unwind fixtures and observer after failure");
    Path entryConfig = Path.of(args[0]).resolveSibling("entry.json");
    Files.writeString(entryConfig, "{\"warmupIterations\":0,\"measurementIterations\":1}");
    var output = new java.io.ByteArrayOutputStream();
    var original = System.out;
    try (var captured = new java.io.PrintStream(output)) {
      System.setOut(captured);
      dev.elide.smh.generated.Main.main(new String[] {entryConfig.toString()});
    } finally {
      System.setOut(original);
    }
    check(
        JsonParser.parseString(output.toString()).getAsJsonArray().size() == 8,
        "Generated entrypoint");
    check(
        dev.elide.smh.fixtures.CountingObserver.before == 8
            && dev.elide.smh.fixtures.CountingObserver.after == 8,
        "Custom observer generated into entrypoint");
    System.out.println("Harness integration checks passed");
  }
}
