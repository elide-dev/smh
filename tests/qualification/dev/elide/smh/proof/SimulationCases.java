// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.proof;

import org.openjdk.jmh.annotations.*;

/** Setup/teardown work varies independently of the measured array sum. */
@State(Scope.Thread)
public class SimulationCases {
  @Param({"16", "1024"})
  public int size;

  @Param({"0"})
  public int fixtureWork;

  public volatile long fixtureSink;
  private int[] values;

  @Setup(Level.Trial)
  public void initialize() {
    values = new int[size];
    for (int i = 0; i < size; i++) values[i] = i;
    fixture();
  }

  @Setup(Level.Iteration)
  public void prepare() {
    fixture();
  }

  @TearDown(Level.Iteration)
  public void finish() {
    fixture();
  }

  @TearDown(Level.Trial)
  public void cleanup() {
    fixture();
  }

  private void fixture() {
    for (int i = 0; i < fixtureWork; i++) fixtureSink += i;
  }

  @Benchmark
  public long sum() {
    long result = 0;
    for (int value : values) result += value;
    return result;
  }
}
