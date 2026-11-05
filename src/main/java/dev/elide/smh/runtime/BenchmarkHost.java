// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import java.net.*;
import java.nio.file.*;
import java.util.*;

/** A reusable host: all orchestration stays here; only the payload is runtime loaded. */
public final class BenchmarkHost {
  private BenchmarkHost() {}

  public static void main(String[] args) throws Throwable {
    if (args.length < 2)
      throw new IllegalArgumentException("usage: smh-host RUN_CONFIG CLASSES [DEPENDENCY ...]");
    RunConfig config = RunConfig.read(args[0]);
    if (config.backend != MeasurementBackend.WALL)
      throw new IllegalArgumentException(
          "Runtime-loaded benchmarks currently support wall measurement only");
    URL[] paths = new URL[args.length - 1];
    for (int i = 1; i < args.length; i++) paths[i - 1] = Path.of(args[i]).toUri().toURL();
    // Parent-first loading preserves the host API's class identity. Keep this alive through
    // teardown: benchmark state, exceptions, and resource lookups can still reference the loader.
    try (URLClassLoader loader = new URLClassLoader(paths, BenchmarkSuite.class.getClassLoader())) {
      Class<?> type = loader.loadClass("dev.elide.smh.generated.Benchmarks");
      if (type.getClassLoader() != loader)
        throw new IllegalStateException(
            "Benchmark payload was included in the host image/classpath");
      BenchmarkSuite suite = (BenchmarkSuite) type.getConstructor().newInstance();
      Thread thread = Thread.currentThread();
      ClassLoader previous = thread.getContextClassLoader();
      thread.setContextClassLoader(loader);
      try {
        System.out.println(Runner.run(suite.benchmarks(), config, suite.observer()));
      } finally {
        thread.setContextClassLoader(previous);
      }
    }
  }
}
