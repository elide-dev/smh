// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.examples;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 20, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 2, time = 20, timeUnit = TimeUnit.MILLISECONDS)
public class ArraySum {
  @Param({"16", "1024"})
  public int size;

  private int[] values;

  @Setup
  public void setup() {
    values = new int[size];
    for (int i = 0; i < size; i++) values[i] = i;
  }

  @Benchmark
  public long sum() {
    long result = 0;
    for (int value : values) result += value;
    return result;
  }
}
