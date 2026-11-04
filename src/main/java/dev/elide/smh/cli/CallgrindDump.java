// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import java.math.BigInteger;
import java.util.*;

/**
 * Strict summary reader; positions and compressed call-graph records do not affect event totals.
 */
record CallgrindDump(
    int pid,
    int part,
    int thread,
    String creator,
    String trigger,
    Map<String, String> caches,
    Map<String, BigInteger> events) {
  static CallgrindDump parse(String text) {
    Map<String, String> fields = new HashMap<>();
    Map<String, String> caches = new TreeMap<>();
    String trigger = null;
    for (String line : text.lines().toList()) {
      int colon = line.indexOf(':');
      if (colon < 0) continue;
      String key = line.substring(0, colon), value = line.substring(colon + 1).strip();
      if (Set.of("version", "creator", "pid", "part", "thread", "events", "summary", "totals")
          .contains(key)) {
        if (fields.put(key, value) != null)
          throw new IllegalArgumentException("Duplicate Callgrind header: " + key);
      } else if (line.startsWith("desc: Trigger: ")) {
        if (trigger != null) throw new IllegalArgumentException("Duplicate Callgrind trigger");
        trigger = line.substring("desc: Trigger: ".length());
      } else if (line.startsWith("desc: ") && value.contains(" cache: ")) {
        String cache = value.substring(0, value.indexOf(" cache: "));
        if (caches.put(cache, value.substring(value.indexOf(" cache: ") + 8)) != null)
          throw new IllegalArgumentException("Duplicate cache description");
      }
    }
    if (!"1".equals(fields.get("version")) || trigger == null)
      throw new IllegalArgumentException("Missing Callgrind version/trigger");
    String creator = required(fields, "creator");
    if (!creator.startsWith("callgrind-"))
      throw new IllegalArgumentException("Not a Callgrind producer");
    List<String> names = Arrays.asList(required(fields, "events").split("\\s+"));
    if (!names.contains("Ir")
        || new HashSet<>(names).size() != names.size()
        || names.stream().anyMatch(n -> !n.matches("[A-Za-z][A-Za-z0-9_]*")))
      throw new IllegalArgumentException("Invalid Callgrind event inventory");
    Map<String, BigInteger> summary = counts(names, required(fields, "summary"));
    if (!summary.equals(counts(names, required(fields, "totals"))))
      throw new IllegalArgumentException("Callgrind summary and totals disagree");
    return new CallgrindDump(
        positive(fields, "pid"),
        positive(fields, "part"),
        positive(fields, "thread"),
        creator,
        trigger,
        Map.copyOf(caches),
        Map.copyOf(summary));
  }

  private static String required(Map<String, String> fields, String key) {
    String value = fields.get(key);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Missing Callgrind header: " + key);
    return value;
  }

  private static int positive(Map<String, String> fields, String key) {
    int value = Integer.parseInt(required(fields, key));
    if (value < 1) throw new IllegalArgumentException("Invalid Callgrind " + key);
    return value;
  }

  private static Map<String, BigInteger> counts(List<String> events, String text) {
    String[] values = text.split("\\s+");
    if (values.length > events.size()) throw new IllegalArgumentException("Too many event counts");
    Map<String, BigInteger> result = new TreeMap<>();
    for (int i = 0; i < events.size(); i++) {
      String value = i < values.length ? values[i] : "0"; // Callgrind omits trailing zero counts.
      if (!value.matches("[0-9]+"))
        throw new IllegalArgumentException("Invalid event count: " + value);
      result.put(events.get(i), new BigInteger(value));
    }
    return result;
  }
}
