// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.proof;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Real generated adapters for each JMH carrier; runtime input prevents literal folding. */
@State(Scope.Thread)
public class SinkCases {
  @Param({"37"})
  public long seed;

  @Benchmark
  public boolean bool() {
    return (seed & 1) == 0;
  }

  @Benchmark
  public byte small() {
    return (byte) (seed * 31);
  }

  @Benchmark
  public short medium() {
    return (short) (seed * 31);
  }

  @Benchmark
  public char character() {
    return (char) (seed * 31);
  }

  @Benchmark
  public int integer() {
    return (int) (seed * 31);
  }

  @Benchmark
  public long large() {
    return seed * 31;
  }

  @Benchmark
  public float fraction() {
    return seed * 1.337f;
  }

  @Benchmark
  public double precise() {
    return seed * 1.337;
  }

  @Benchmark
  public Object object() {
    return new Payload(seed);
  }

  @Benchmark
  public Object[] references() {
    return new Object[] {new Payload(seed)};
  }

  @Benchmark
  public long[] primitives() {
    return new long[] {seed};
  }

  @Benchmark
  public void explicit(Blackhole blackhole) {
    blackhole.consume(seed * 31);
  }

  private record Payload(long value) {}
}
