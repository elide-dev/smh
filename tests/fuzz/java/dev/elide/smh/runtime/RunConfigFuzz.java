// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Raw JSON/schema exploration and an independent valid-configuration round-trip oracle. */
public final class RunConfigFuzz {
  public static void fuzzerTestOneInput(byte[] input) {
    String text = new String(input, StandardCharsets.UTF_8);
    try {
      RunConfig.fromJson(JsonParser.parseString(text)).validate();
    } catch (JsonParseException | IllegalArgumentException | ArithmeticException expected) {
      // Only documented syntax, schema, and exact-number validation failures are expected.
    }
    long duration =
        new BigInteger(1, input).mod(BigInteger.valueOf(Long.MAX_VALUE)).longValue() + 1;
    int batch = input.length % 1024 + 1;
    JsonObject document = new JsonObject();
    document.addProperty("measurementNanos", duration);
    document.addProperty("wallBatchSize", batch);
    JsonObject params = new JsonObject();
    JsonArray values = new JsonArray();
    values.add(text);
    params.add("text", values);
    document.add("params", params);
    RunConfig parsed = RunConfig.fromJson(JsonParser.parseString(document.toString()));
    if (parsed.measurementNanos != duration
        || parsed.wallBatchSize != batch
        || !parsed.params.get("text").equals(List.of(text)))
      throw new AssertionError("Configuration round trip changed exact values");
    JsonObject simulation = new JsonObject();
    simulation.addProperty("backend", "callgrind");
    simulation.addProperty("invocationsPerIteration", duration);
    RunConfig simulated = RunConfig.fromJson(simulation);
    if (simulated.backend != MeasurementBackend.CALLGRIND
        || simulated.invocationsPerIteration != duration)
      throw new AssertionError("Simulation work budget changed during parsing");
  }
}
