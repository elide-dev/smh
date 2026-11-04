// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.proof;

import com.google.gson.*;
import dev.elide.smh.generated.Benchmarks;
import dev.elide.smh.runtime.*;
import java.util.Map;

/** Separate the boundary call from sinks; generated adapters match real user benchmark builds. */
public final class ProofMain {
  static long measure(BenchmarkCase benchmark) throws Throwable {
    long start = System.nanoTime();
    benchmark.invoke();
    return System.nanoTime() - start;
  }

  static void scalarDiscarded(long input) {
    long discarded = input * 31;
  }

  public static void main(String[] args) throws Throwable {
    if (args[0].equals("--policy")) {
      JsonArray flags = new JsonArray();
      AotPolicy.requiredCompilerFlags().forEach(flags::add);
      System.out.println(flags);
      return;
    }
    JsonArray results = new JsonArray();
    for (BenchmarkSpec spec : Benchmarks.all()) {
      BenchmarkCase benchmark = spec.factory().apply(Map.of("seed", args[0]));
      JsonObject row = new JsonObject();
      row.addProperty("benchmark", spec.name());
      row.addProperty("adapter", benchmark.getClass().getName() + ".invoke");
      row.addProperty("sink", benchmark.sink());
      if (args.length > 1) row.addProperty("elapsedNanos", measure(benchmark));
      benchmark.tearDown("Trial");
      results.add(row);
    }
    scalarDiscarded(Long.parseLong(args[0]));
    System.out.println(results);
  }
}
