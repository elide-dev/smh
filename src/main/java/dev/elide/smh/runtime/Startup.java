// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Readiness notification for startup experiments; harmless during ordinary benchmark execution. */
public final class Startup {
  private static volatile String endpoint;
  private static String token;
  private static final AtomicBoolean sent = new AtomicBoolean();

  private Startup() {}

  /** Call when an application is ready, typically from trial setup. First notification wins. */
  public static void ready() {
    if ("ready".equals(endpoint)) signal();
  }

  private static void signal() {
    if (sent.compareAndSet(false, true)) reached();
  }

  /** Preserved, non-inlined Callgrind dump-before boundary. Notification follows the snapshot. */
  public static void reached() {
    System.out.println("SMH_STARTUP " + token);
    System.out.flush();
  }

  static JsonArray run(List<BenchmarkSpec> specs, RunConfig config, boolean discovery)
      throws Throwable {
    return run(specs, config, discovery, System.getenv("SMH_STARTUP_TOKEN"));
  }

  static JsonArray run(
      List<BenchmarkSpec> specs, RunConfig config, boolean discovery, String launchToken)
      throws Throwable {
    List<BenchmarkSpec> cases = new ArrayList<>();
    List<Map<String, String>> parameters = new ArrayList<>();
    JsonArray descriptions = new JsonArray();
    for (BenchmarkSpec spec : specs) {
      Map<String, List<String>> dimensions = new TreeMap<>(spec.params());
      config.params.forEach(
          (key, values) -> {
            if (dimensions.containsKey(key)) dimensions.put(key, values);
          });
      List<Map<String, String>> combinations = new ArrayList<>();
      Runner.matrix(new ArrayList<>(dimensions.entrySet()), 0, new LinkedHashMap<>(), combinations);
      for (Map<String, String> params : combinations) {
        cases.add(spec);
        parameters.add(params);
        JsonObject row = new JsonObject();
        row.addProperty("benchmark", spec.name());
        row.add("params", new Gson().toJsonTree(params));
        row.addProperty("jdkVersion", System.getProperty("java.version"));
        row.addProperty("vmName", System.getProperty("java.vm.name"));
        descriptions.add(row);
      }
    }
    if (discovery) return descriptions;
    if (cases.size() != 1)
      throw new IllegalArgumentException(
          "Startup requires one case per fresh process; use smh bench --mode startup");
    token = launchToken;
    if (token == null)
      throw new IllegalStateException("Startup requires the smh bench parent launcher");
    sent.set(false);
    endpoint = config.until == null ? "first-result" : config.until;
    try {
      BenchmarkCase benchmark =
          Objects.requireNonNull(cases.getFirst().factory().apply(parameters.getFirst()));
      lifecycle(
          benchmark,
          "Trial",
          () -> {
            if (endpoint.equals("ready")) ready();
            else
              lifecycle(
                  benchmark,
                  "Iteration",
                  () ->
                      lifecycle(
                          benchmark,
                          "Invocation",
                          () -> {
                            benchmark.invoke();
                            if (endpoint.equals("first-result")) signal();
                          }));
          });
      return descriptions;
    } finally {
      endpoint = null;
    }
  }

  private static void lifecycle(BenchmarkCase benchmark, String level, Runner.Cleanup work)
      throws Throwable {
    benchmark.setup(level);
    Throwable failure = null;
    try {
      work.run();
    } catch (Throwable error) {
      failure = error;
      throw error;
    } finally {
      Runner.cleanup(failure, () -> benchmark.tearDown(level));
    }
  }
}
