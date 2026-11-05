// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package example.invoice;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 100, timeUnit = TimeUnit.MILLISECONDS)
public class InvoiceBenchmark {
  @Param({"10", "1000"})
  public int lines;

  private String invoice;

  @Setup
  public void setup() {
    invoice = "2:1250\n".repeat(lines);
  }

  @Benchmark
  public long total() {
    return InvoiceApp.total(invoice);
  }
}
