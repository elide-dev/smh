// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.*;
import java.util.function.LongSupplier;

/** Bounded geometric search; every probe is retained and no overhead is subtracted. */
final class BatchCalibrator {
  private BatchCalibrator() {}

  record Result(int batchSize, JsonObject report) {}

  static Result calibrate(
      BenchmarkCase benchmark, LongSupplier clock, BatchCalibrationPolicy policy) throws Throwable {
    int size = 1;
    long invocations = 0;
    JsonArray attempts = new JsonArray();
    String status, reason;
    while (true) {
      JsonArray elapsed = new JsonArray();
      long minimum = Long.MAX_VALUE;
      for (int sample = 0; sample < policy.samplesPerAttempt(); sample++) {
        benchmark.setup("Iteration");
        Throwable failure = null;
        long nanos;
        try {
          nanos = Runner.timedBatch(benchmark, clock, size);
        } catch (Throwable t) {
          failure = t;
          throw t;
        } finally {
          Runner.cleanup(failure, () -> benchmark.tearDown("Iteration"));
        }
        elapsed.add(nanos);
        minimum = Math.min(minimum, nanos);
        invocations = Math.addExact(invocations, size);
      }
      JsonObject attempt = new JsonObject();
      attempt.addProperty("batchSize", size);
      attempt.add("elapsedNanos", elapsed);
      attempts.add(attempt);
      if (minimum >= policy.targetNanos()) {
        status = "TARGET_REACHED";
        reason = "all-probes-met-duration";
        break;
      }
      status = "EXHAUSTED";
      if (size == policy.maximumBatchSize()) {
        reason = "maximum-batch-size";
        break;
      }
      if (attempts.size() == policy.maximumAttempts()) {
        reason = "maximum-attempts";
        break;
      }
      size = (int) Math.min(policy.maximumBatchSize(), 2L * size);
    }
    JsonObject report = new JsonObject();
    report.addProperty("status", status);
    report.addProperty("stoppingReason", reason);
    report.addProperty("selectedBatchSize", size);
    report.addProperty("targetNanos", policy.targetNanos());
    report.addProperty("maximumBatchSize", policy.maximumBatchSize());
    report.addProperty("maximumAttempts", policy.maximumAttempts());
    report.addProperty("samplesPerAttempt", policy.samplesPerAttempt());
    report.addProperty("invocations", invocations);
    report.add("attempts", attempts);
    return new Result(size, report);
  }
}
