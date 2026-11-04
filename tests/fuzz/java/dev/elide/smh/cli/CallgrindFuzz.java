// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Raw malformed-input exploration plus an independent exact-counter construction oracle. */
public final class CallgrindFuzz {
  public static void fuzzerTestOneInput(byte[] input) {
    CallgrindDump parsed;
    try {
      parsed = CallgrindDump.parse(new String(input, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException expected) {
      parsed = null;
    }
    if (parsed != null) {
      if (parsed.pid() <= 0
          || parsed.part() <= 0
          || parsed.thread() <= 0
          || !parsed.events().containsKey("Ir")
          || parsed.events().values().stream().anyMatch(value -> value.signum() < 0))
        throw new AssertionError("Accepted invalid Callgrind counters or identities");
    }
    // These inputs are always valid. A rejection here must not be swallowed.
    BigInteger expected = new BigInteger(1, input);
    String header =
        "version: 1\ncreator: callgrind-fuzz\npid: 1\npart: 1\nthread: 1\n"
            + "desc: Trigger: Program termination\nevents: Ir Dr\n";
    String text = header + "summary: " + expected + "\ntotals: " + expected + " 0\n";
    CallgrindDump exact = CallgrindDump.parse(text);
    if (!exact.events().equals(Map.of("Ir", expected, "Dr", BigInteger.ZERO)))
      throw new AssertionError("Counter precision lost or omitted trailing zero changed");
  }
}
