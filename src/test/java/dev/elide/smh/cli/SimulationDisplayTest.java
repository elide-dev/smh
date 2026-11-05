// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("portable")
class SimulationDisplayTest {
  @TempDir Path directory;

  static JsonObject report(String... instructions) {
    JsonObject report =
        JsonParser.parseString(
                """
                {"schemaVersion":1,"backend":"callgrind","quantity":"simulated-events",
                 "creator":"callgrind-3.25.1","cacheModel":{"I1":"32768,8,64"},
                 "architecture":"AMD64","purpose":"exploratory-measurement",
                 "results":[{"benchmark":"Invoice.total","params":{"items":"100"},
                 "status":"collected","backend":"callgrind","region":"batch","sink":"blackhole",
                 "warmupIterations":2,"invocationsPerIteration":10,"operationsPerIteration":20}]}
                """)
            .getAsJsonObject();
    JsonObject row = row(report);
    row.addProperty("measurementIterations", instructions.length);
    JsonArray samples = new JsonArray();
    for (String value : instructions) {
      JsonObject sample =
          JsonParser.parseString(
                  """
                  {"operations":"20","events":{"Dr":"100","Dw":"40","D1mr":"2","DLmr":"0","Bcm":"4"}}
                  """)
              .getAsJsonObject();
      sample.getAsJsonObject("events").addProperty("Ir", value);
      samples.add(sample);
    }
    row.add("observations", samples);
    return report;
  }

  static JsonObject row(JsonObject report) {
    return report.getAsJsonArray("results").get(0).getAsJsonObject();
  }

  static JsonObject sample(JsonObject report, int index) {
    return row(report).getAsJsonArray("observations").get(index).getAsJsonObject();
  }

  static String render(JsonObject report, JsonObject baseline, int width) {
    var bytes = new ByteArrayOutputStream();
    SimulationDisplay.render(report, baseline, new PrintStream(bytes), width);
    return bytes.toString();
  }

  static String plain(String text) {
    return text.replaceAll("\033\\[[0-9;]*m", "");
  }

  @Test
  void exactCountsAreNormalizedBeforeRoundingOrConvertingToDouble() {
    JsonObject report = report("1208925819614629174706176", "1208925819614629174706177");
    assertEquals(
        new BigDecimal("60446290980731458735308.825"), SimulationDisplay.mean(row(report), "Ir"));
    assertEquals(new BigDecimal("5"), SimulationDisplay.mean(row(report), "Dr"));
    assertEquals(new BigDecimal("0.1"), SimulationDisplay.mean(row(report), "D1mr"));
    assertEquals("Instruction counts vary", SimulationDisplay.repeatability(row(report)));
    assertEquals("1E+400", SimulationDisplay.number(new BigDecimal("1e400")));
    assertEquals("1E-400", SimulationDisplay.number(new BigDecimal("1e-400")));
    assertEquals(ResultDetails.number(0), SimulationDisplay.number(BigDecimal.ZERO));
    assertEquals("24.8k", SimulationDisplay.number(new BigDecimal("24816")));
    assertEquals("24.8m", SimulationDisplay.number(new BigDecimal("24816000")));
    assertEquals("24.8b", SimulationDisplay.number(new BigDecimal("24816000000")));
    assertEquals("unchanged", SimulationDisplay.change(BigDecimal.ONE, BigDecimal.ONE));
    assertEquals(
        "Δ unavailable (zero baseline)", SimulationDisplay.change(BigDecimal.ONE, BigDecimal.ZERO));
    assertTrue(SimulationDisplay.change(new BigDecimal("21"), BigDecimal.TEN).startsWith("+"));
    assertTrue(SimulationDisplay.change(BigDecimal.ONE, BigDecimal.TEN).startsWith("−"));
    assertEquals("+<1%", SimulationDisplay.change(new BigDecimal("100.1"), new BigDecimal("100")));
    assertEquals("−<1%", SimulationDisplay.change(new BigDecimal("99.9"), new BigDecimal("100")));
  }

  @Test
  void comparisonHasSharedZeroBasedScalesAndHonestSimulationLabels() {
    JsonObject baseline = report("200", "200"), current = report("100", "100");
    String output = render(current, baseline, 80), plain = plain(output);
    assertTrue(output.contains(TerminalDisplay.CYAN));
    assertTrue(output.contains(TerminalDisplay.VIOLET));
    assertTrue(plain.contains("instructions/op"));
    assertTrue(plain.contains("−" + ResultDetails.number(50) + "% observed"));
    assertTrue(plain.contains("Callgrind · simulated events"));
    assertTrue(plain.contains("accuracy unqualified"));
    assertTrue(plain.contains("Host/build equivalence unchecked"));
    assertTrue(plain.contains("Instruction counts identical"));
    assertFalse(plain.contains("Instructions/op · iteration order"));
    var baselineBar = plain.lines().filter(s -> s.contains("baseline │")).findFirst().orElseThrow();
    var currentBar = plain.lines().filter(s -> s.contains("current  │")).findFirst().orElseThrow();
    assertEquals(48, baselineBar.chars().filter(c -> c == '█').count());
    assertEquals(24, currentBar.chars().filter(c -> c == '█').count());
    assertTrue(plain.contains("Data writes/op"));
    assertTrue(plain.contains("Conditional branch misses/op"));
    assertFalse(plain.contains("D1 read misses/op"));
    assertFalse(plain.contains("Last-level read misses/op"));
    assertFalse(plain.contains("µs"));
  }

  @Test
  void standaloneTraceUsesEveryObservationAndLabelsItsRangeAndGrouping() {
    JsonObject current = report("100", "140", "120");
    String output = plain(render(current, null, 80));
    assertTrue(output.contains("▁█▅"));
    assertTrue(output.contains("1 → 3"));
    assertTrue(output.contains("Instruction counts vary"));
    assertFalse(output.contains("baseline │"));
    assertTrue(output.contains("scale 0 → " + ResultDetails.number(5)));
    String[] samples = new String[100];
    Arrays.fill(samples, "100");
    samples[1] = "1000";
    output = plain(render(report(samples), null, 40));
    assertTrue(output.contains("peak per group"));
    assertTrue(output.contains("█"));
    assertTrue(output.contains("1 → 100"));
    assertTrue(plain(render(report("100"), null, 40)).contains("Repeatability: not assessed"));
    assertTrue(plain(render(report("100"), report("100"), 40)).contains("unchanged observed"));
    current.remove("purpose");
    sample(current, 0).getAsJsonObject("events").remove("Bcm");
    sample(current, 1).getAsJsonObject("events").remove("Bcm");
    sample(current, 2).getAsJsonObject("events").remove("Bcm");
    assertFalse(plain(render(current, null, 160)).contains("Conditional branch misses"));
    JsonObject baseline = report("100");
    row(baseline).addProperty("operationsPerIteration", 40);
    sample(baseline, 0).addProperty("operations", "40");
    String narrow = plain(render(report("100"), baseline, 40));
    assertTrue(
        narrow.contains(
            "Comparison unavailable:\n    operationsPerIteration\n    differs or is missing"));
    assertTrue(narrow.contains("Event bars start at zero."));
    assertTrue(narrow.lines().allMatch(line -> line.length() <= 40), narrow);
    row(baseline).getAsJsonObject("params").addProperty("items", "200");
    narrow = plain(render(report("100"), baseline, 40));
    assertTrue(narrow.contains("no matching baseline"));
    assertTrue(narrow.lines().allMatch(line -> line.length() <= 40), narrow);
  }

  @Test
  void modelAndWorkMismatchesCannotProduceComparisonBars() {
    for (String field : List.of("creator", "cacheModel", "architecture", "purpose")) {
      JsonObject baseline = report("100");
      baseline.remove(field);
      assertTrue(
          plain(render(report("100"), baseline, 80)).contains(field + " differs or is missing"));
      baseline.addProperty(field, "other");
      assertTrue(
          plain(render(report("100"), baseline, 80)).contains(field + " differs or is missing"));
      JsonObject current = report("100");
      current.remove(field);
      assertFalse(plain(render(current, report("100"), 80)).contains("baseline │"));
    }
    for (String field : List.of("region", "sink", "warmupIterations", "invocationsPerIteration")) {
      JsonObject baseline = report("100");
      row(baseline).remove(field);
      assertTrue(
          plain(render(report("100"), baseline, 80)).contains(field + " differs or is missing"));
      row(baseline).addProperty(field, "other");
      assertFalse(plain(render(report("100"), baseline, 80)).contains("baseline │"));
      JsonObject current = report("100");
      row(current).remove(field);
      assertFalse(plain(render(current, report("100"), 80)).contains("baseline │"));
    }
    assertTrue(
        plain(render(report("100"), report("100", "100"), 80))
            .contains("measurementIterations differs"));
    JsonObject baseline = report("100");
    row(baseline).addProperty("operationsPerIteration", 40);
    sample(baseline, 0).addProperty("operations", "40");
    assertTrue(
        plain(render(report("100"), baseline, 80)).contains("operationsPerIteration differs"));
    baseline = report("100");
    sample(baseline, 0).getAsJsonObject("events").remove("Dr");
    assertTrue(plain(render(report("100"), baseline, 80)).contains("event sets differ"));
  }

  @Test
  void identityIncludesParametersAndUnmatchedCasesAreCounted() {
    JsonObject baseline = report("100");
    row(baseline).getAsJsonObject("params").addProperty("items", "200");
    String output = plain(render(report("100"), baseline, 80));
    assertTrue(output.contains("no matching baseline"));
    assertTrue(output.contains("1 current / 1 baseline unmatched"));
    JsonObject extra = row(baseline).deepCopy();
    extra.addProperty("benchmark", "extra");
    baseline.getAsJsonArray("results").add(extra);
    assertTrue(
        plain(render(report("100"), baseline, 80)).contains("1 current / 2 baseline unmatched"));
    baseline = report("100");
    baseline.getAsJsonArray("results").add(extra);
    assertTrue(
        plain(render(report("100"), baseline, 80)).contains("0 current / 1 baseline unmatched"));
    JsonObject current = report("100");
    current.getAsJsonArray("results").add(extra);
    assertTrue(
        plain(render(current, report("100"), 80)).contains("1 current / 0 baseline unmatched"));
  }

  @Test
  void barsHandleZeroAndFractionalCellsWithoutInventingCounts() {
    var text = new StringBuilder();
    SimulationDisplay.bar(text, "current", BigDecimal.ZERO, BigDecimal.ZERO, 12, "");
    assertTrue(plain(text.toString()).contains("│────────────"));
    text.setLength(0);
    SimulationDisplay.bar(text, "current", BigDecimal.ONE, new BigDecimal("16"), 12, "");
    assertTrue(plain(text.toString()).contains("│▊───────────"));
    text.setLength(0);
    SimulationDisplay.bar(text, "current", BigDecimal.TEN, BigDecimal.TEN, 12, "");
    assertEquals(12, text.chars().filter(c -> c == '█').count());
    assertTrue(plain(render(report("0"), report("0"), 80)).contains("zero baseline"));
  }

  @Test
  void baselineValidationRejectsNonResultsAndInvalidRawObservations() throws Exception {
    Path file = directory.resolve("baseline.json");
    JsonObject good = report("100", "100");
    Files.writeString(file, good.toString());
    assertEquals(good, SimulationDisplay.read(file));
    for (String invalid : List.of("[]", "[1]", "{}", "broken")) {
      Files.writeString(file, invalid);
      assertThrows(IllegalArgumentException.class, () -> SimulationDisplay.read(file));
    }
    List<Consumer<JsonObject>> mutations =
        List.of(
            r -> r.addProperty("schemaVersion", 2),
            r -> r.addProperty("backend", "wall"),
            r -> r.addProperty("quantity", "wall-time"),
            r -> row(r).addProperty("status", "pending-counter-import"),
            r -> row(r).addProperty("backend", "wall"),
            r -> row(r).remove("params"),
            r -> r.getAsJsonArray("results").add(row(r).deepCopy()),
            r -> r.add("results", new JsonArray()),
            r -> row(r).add("observations", new JsonArray()),
            r -> row(r).addProperty("measurementIterations", 3),
            r -> row(r).addProperty("measurementIterations", 1.5),
            r -> row(r).addProperty("operationsPerIteration", 0),
            r -> row(r).addProperty("operationsPerIteration", 1.5),
            r -> sample(r, 0).getAsJsonObject("events").remove("Ir"),
            r -> sample(r, 1).getAsJsonObject("events").remove("Dr"),
            r -> sample(r, 0).addProperty("operations", 21),
            r -> sample(r, 0).getAsJsonObject("events").addProperty("Dr", "-1"),
            r -> sample(r, 0).getAsJsonObject("events").addProperty("Dr", "0.5"));
    for (Consumer<JsonObject> mutation : mutations) {
      JsonObject invalid = good.deepCopy();
      mutation.accept(invalid);
      Files.writeString(file, invalid.toString());
      assertThrows(IllegalArgumentException.class, () -> SimulationDisplay.read(file));
    }
    assertThrows(IOException.class, () -> SimulationDisplay.read(directory.resolve("missing")));
  }

  @Test
  void presentationKeepsRedirectedJsonUnchangedAndUsesExistingDisplayPolicy() {
    JsonObject report = report("100"), baseline = report("200");
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    for (TerminalDisplay.Mode mode :
        List.of(TerminalDisplay.Mode.auto, TerminalDisplay.Mode.plain)) {
      SimulationDisplay.publish(
          report,
          null,
          mode,
          false,
          Map.of(),
          new PrintStream(out),
          new PrintStream(err),
          directory);
      assertEquals(report + System.lineSeparator(), out.toString());
      assertEquals("", err.toString());
      out.reset();
    }
    SimulationDisplay.publish(
        report,
        baseline,
        TerminalDisplay.Mode.tui,
        false,
        Map.of(),
        new PrintStream(out),
        new PrintStream(err),
        directory);
    assertEquals(report + System.lineSeparator(), out.toString());
    assertTrue(err.toString().contains("baseline"));
    assertTrue(err.toString().contains("results.json"));
    out.reset();
    err.reset();
    SimulationDisplay.publish(
        report,
        null,
        TerminalDisplay.Mode.auto,
        true,
        Map.of("COLUMNS", "80"),
        new PrintStream(out),
        new PrintStream(err),
        directory);
    assertEquals("", out.toString());
    assertTrue(err.toString().contains("Repeatability: not assessed"));
    out.reset();
    err.reset();
    SimulationDisplay.publish(
        new JsonObject(),
        null,
        TerminalDisplay.Mode.tui,
        false,
        Map.of(),
        new PrintStream(out),
        new PrintStream(err),
        directory);
    assertEquals("{}" + System.lineSeparator(), out.toString());
    assertTrue(err.toString().contains("No simulation summary available"));
    for (Map<String, String> env :
        List.of(Map.of("NO_COLOR", ""), Map.of("CI", "true"), Map.of("TERM", "dumb")))
      assertThrows(
          IllegalArgumentException.class,
          () -> SimulationDisplay.visual(TerminalDisplay.Mode.auto, true, env, baseline));
    assertThrows(
        IllegalArgumentException.class,
        () -> SimulationDisplay.visual(TerminalDisplay.Mode.auto, false, Map.of(), baseline));
    assertThrows(
        IllegalArgumentException.class,
        () -> SimulationDisplay.visual(TerminalDisplay.Mode.plain, true, Map.of(), baseline));
  }

  @Test
  void commandValidatesBaselineBeforeRunningAndHasExplicitDisplayOptions() throws Exception {
    Path bad = Files.writeString(directory.resolve("bad.json"), "[]");
    Main.Simulate command = new Main.Simulate();
    command.compare = bad;
    command.display = TerminalDisplay.Mode.tui;
    assertThrows(IllegalArgumentException.class, command::call); // No runner injected or invoked.
    Path valid = Files.writeString(directory.resolve("valid.json"), report("100").toString());
    command.compare = valid;
    command.display = TerminalDisplay.Mode.plain;
    assertThrows(IllegalArgumentException.class, command::call);
    CommandLine cli = new CommandLine(new Main.Simulate());
    cli.parseArgs(
        "binary",
        "--config",
        "run.json",
        "--output",
        "result",
        "--display",
        "tui",
        "--compare",
        valid.toString());
    Main.Simulate parsed = cli.getCommand();
    assertEquals(TerminalDisplay.Mode.tui, parsed.display);
    assertEquals(valid, parsed.compare);
  }

  @Test
  void reportTextCannotInjectTerminalControlSequences() {
    JsonObject report = report("100");
    row(report).addProperty("benchmark", "bad\033[2J\nname");
    row(report).getAsJsonObject("params").addProperty("items", "\033[1m");
    report.addProperty("purpose", "bad\rpurpose");
    String output = render(report, null, 80);
    assertFalse(output.contains("\033[2J"));
    assertTrue(output.contains("bad?[2J?name"));
    assertTrue(output.contains("bad?purpose"));
  }

  @Test
  void soakUsesActualWindowBudgetsAndKeepsTimelineWithBaseline() {
    JsonObject report = report("100", "60");
    JsonObject row = row(report);
    row.addProperty("experimentMode", "soak");
    row.addProperty("requestedOperations", 30);
    row.add("operationCounts", JsonParser.parseString("[20,10]"));
    sample(report, 1).addProperty("operations", "10");
    assertEquals(1, SimulationDisplay.rows(report).size());
    assertTrue(render(report, null, 140).contains("Completed operations"));
    assertTrue(render(report, report, 140).contains("Window distribution"));
    JsonObject changed = row.deepCopy();
    changed.addProperty("requestedOperations", 40);
    assertTrue(SimulationDisplay.workDifference(row, changed).contains("requestedOperations"));
    changed = row.deepCopy();
    changed.add("operationCounts", JsonParser.parseString("[10,20]"));
    assertTrue(SimulationDisplay.workDifference(row, changed).contains("operationCounts"));
    changed = row.deepCopy();
    changed.addProperty("experimentMode", "steady");
    assertTrue(SimulationDisplay.workDifference(row, changed).contains("experimentMode"));
    JsonObject old = report("100"), current = old.deepCopy();
    row(current).addProperty("experimentMode", "steady");
    assertEquals("", SimulationDisplay.workDifference(row(current), row(old)));
    for (String count : List.of("0", "21")) {
      sample(report, 1).addProperty("operations", count);
      assertThrows(IllegalArgumentException.class, () -> SimulationDisplay.rows(report));
    }
    sample(report, 1).addProperty("operations", "9");
    assertThrows(IllegalArgumentException.class, () -> SimulationDisplay.rows(report));
  }
}
