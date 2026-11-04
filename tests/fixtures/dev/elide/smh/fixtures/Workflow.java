// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.fixtures;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
public class Workflow {
  public enum Kind {
    A,
    B
  }

  @Param({"2", "3"})
  public int size;

  @Param public Kind kind;
  public static int trials, iterations, invocations, trialEnds, iterationEnds, invocationEnds;
  private int stage;

  @Setup(Level.Trial)
  public void trial() {
    check(stage == 0);
    stage = 1;
    trials++;
  }

  @Setup(Level.Iteration)
  public void iteration() {
    check(stage == 1);
    stage = 2;
    iterations++;
  }

  @Setup(Level.Invocation)
  public void invocation() {
    check(stage == 2);
    stage = 3;
    invocations++;
  }

  @TearDown(Level.Invocation)
  public void invocationEnd() {
    check(stage == 3);
    stage = 2;
    invocationEnds++;
  }

  @TearDown(Level.Iteration)
  public void iterationEnd() {
    check(stage == 2);
    stage = 1;
    iterationEnds++;
  }

  @TearDown(Level.Trial)
  public void trialEnd() {
    check(stage == 1);
    stage = 0;
    trialEnds++;
  }

  @Benchmark
  public int work(Data data) {
    check(stage == 3 && size > 0 && kind != null && data.ready && data.offset > 0);
    return size + data.offset;
  }

  private static void check(boolean value) {
    if (!value) throw new IllegalStateException("Invalid lifecycle");
  }

  @State(Scope.Benchmark)
  public static class Data {
    @Param({"1", "2"})
    public int offset;

    public boolean ready;

    @Setup
    public void setup() {
      ready = true;
    }

    @TearDown
    public void cleanup() {
      ready = false;
    }
  }
}
