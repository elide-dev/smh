// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class TerminalDisplayTest {
  @TempDir Path directory;

  @Test
  void terminalPolicyAndFrames() {
    var auto = TerminalDisplay.Mode.auto;
    assertTrue(TerminalDisplay.enabled(auto, true, Map.of()));
    assertFalse(TerminalDisplay.enabled(auto, false, Map.of()));
    for (var env : List.of(Map.of("CI", "true"), Map.of("NO_COLOR", ""), Map.of("TERM", "dumb")))
      assertFalse(TerminalDisplay.enabled(auto, true, env));
    assertFalse(TerminalDisplay.enabled(TerminalDisplay.Mode.plain, true, Map.of()));
    assertTrue(TerminalDisplay.enabled(TerminalDisplay.Mode.tui, false, Map.of()));
    assertNotEquals(TerminalDisplay.frame(0, 1), TerminalDisplay.frame(1, 1));
    assertEquals(TerminalDisplay.frame(0, 1), TerminalDisplay.frame(120, 1));
    assertEquals("a?b?", TerminalDisplay.clean("a\033b\n"));
    assertEquals("—", TerminalDisplay.sparkline(List.of()));
    assertEquals("▄▄", TerminalDisplay.sparkline(List.of(2.0, 2.0)));
    assertEquals("▁█", TerminalDisplay.sparkline(List.of(1.0, 2.0)));
    assertEquals(40, TerminalDisplay.sparkline(Collections.nCopies(100, 1.0)).length());
  }

  @Test
  void summaryPreservesUnitsAndDoesNotClaimAccuracy() {
    var bytes = new ByteArrayOutputStream();
    var out = new PrintStream(bytes);
    TerminalDisplay.summary(
        """
        [{"benchmark":"bench.Sum","params":{"size":"16"},"mode":"avgt",
          "primaryMetric":{"score":12.5,"scoreUnit":"ns/op","rawData":[[10,15]]}}]
        """,
        out);
    String text = bytes.toString();
    assertTrue(text.contains("bench.Sum"));
    assertTrue(text.contains("12.5 ns/op"));
    assertTrue(text.contains("2 iterations"));
    assertFalse(text.contains("unqualified"));
    TerminalDisplay.summary(
        "[{\"benchmark\":\"empty\",\"params\":{},\"mode\":\"avgt\",\"primaryMetric\":{\"score\":1,\"scoreUnit\":\"ns/op\",\"rawData\":[[]]}}]",
        out);
    TerminalDisplay.summary("not json", out);
    TerminalDisplay.summary("[]", out);
    assertTrue(bytes.toString().contains("No wall-time summary"));
  }

  @Test
  void trueColorAndComparisonUseRealScores() throws Exception {
    assertEquals("\033[38;2;1;2;3m", TerminalDisplay.rgb(1, 2, 3));
    assertEquals(
        TerminalDisplay.deltaColor(OptionalDouble.empty(), "avgt"),
        TerminalDisplay.deltaColor(OptionalDouble.of(0), "avgt"));
    assertEquals(
        TerminalDisplay.deltaColor(OptionalDouble.of(-10), "avgt"),
        TerminalDisplay.deltaColor(OptionalDouble.of(-10), "thrpt"));
    assertEquals(
        TerminalDisplay.deltaColor(OptionalDouble.of(10), "avgt"),
        TerminalDisplay.deltaColor(OptionalDouble.of(10), "thrpt"));
    assertNotEquals(
        TerminalDisplay.deltaColor(OptionalDouble.of(-10), "avgt"),
        TerminalDisplay.deltaColor(OptionalDouble.of(10), "avgt"));
    assertTrue(TerminalDisplay.gradient("▁▂█", 0).contains("38;2;"));
    assertNotEquals(TerminalDisplay.gradient("▁▂█", 0), TerminalDisplay.gradient("▁▂█", 10));
    var baseline =
        new BenchmarkComparison(BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(100)));
    var current = BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(125));
    var stderr = new ByteArrayOutputStream();
    TerminalDisplay.summary(current.toString(), new PrintStream(stderr), baseline);
    assertTrue(stderr.toString().contains("+25%"));
    current.get(0).getAsJsonObject().addProperty("benchmark", "unmatched");
    TerminalDisplay.summary(current.toString(), new PrintStream(stderr), baseline);
    assertTrue(stderr.toString().contains("no delta"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TerminalDisplay.run(
                child(""),
                "compare",
                TerminalDisplay.Mode.plain,
                true,
                false,
                Map.of(),
                System.out,
                System.err,
                baseline));
  }

  @Test
  void comparisonScaleIsSharedAndOverflowIsExplicit() {
    assertEquals(80, TerminalDisplay.width(Map.of()));
    assertEquals(80, TerminalDisplay.width(Map.of("COLUMNS", "not a size")));
    assertEquals(40, TerminalDisplay.width(Map.of("COLUMNS", "10")));
    assertEquals(160, TerminalDisplay.width(Map.of("COLUMNS", "200")));
    assertEquals(120, TerminalDisplay.width(Map.of("COLUMNS", "120")));
    assertEquals("0%", TerminalDisplay.percent(0));
    assertEquals("−<1%", TerminalDisplay.percent(-.1));
    assertEquals("+<1%", TerminalDisplay.percent(.1));
    assertEquals("−12%", TerminalDisplay.percent(-12.3));
    for (double value : new double[] {-100, -20, -10, -.1, 0, .1, 10, 20, 100}) {
      String bar = plain(TerminalDisplay.changeBar(value));
      assertEquals(21, bar.length());
      assertEquals(10, bar.indexOf('│'));
      assertEquals(value < -20, bar.contains("◀"));
      assertEquals(value > 20, bar.contains("▶"));
    }
    var row = BenchmarkComparisonTest.row(100);
    row.addProperty("measurementBatchSize", 64);
    row.getAsJsonObject("smh").addProperty("accuracy", "unqualified");
    var baseline = new BenchmarkComparison(BenchmarkComparisonTest.rows(row));
    row.getAsJsonObject("primaryMetric").addProperty("score", 112);
    for (int width : new int[] {40, 64, 80, 140}) {
      String rendered = plain(TerminalDisplay.comparisonRow(row, baseline, width));
      assertTrue(rendered.contains("+12%"));
      assertTrue(rendered.lines().allMatch(line -> line.length() <= width), rendered);
    }
    var bytes = new ByteArrayOutputStream();
    TerminalDisplay.summary(
        BenchmarkComparisonTest.rows(row).toString(), new PrintStream(bytes), null, 80);
    assertTrue(plain(bytes.toString()).contains("Batch 64 invocations"));
    assertTrue(bytes.toString().contains("accuracy unqualified"));
    row.addProperty("measurementBatchSize", 1);
    bytes.reset();
    TerminalDisplay.summary(BenchmarkComparisonTest.rows(row).toString(), new PrintStream(bytes));
    assertFalse(plain(bytes.toString()).contains("Batch 1"));
    var current = BenchmarkComparisonTest.rows(row);
    var throughput = row.deepCopy();
    throughput.addProperty("mode", "thrpt");
    current.add(throughput);
    TerminalDisplay.footer(current.toString(), new PrintStream(bytes), baseline);
    assertTrue(bytes.toString().contains("each row's metric"));
    bytes.reset();
    TerminalDisplay.cards("[]", new PrintStream(bytes), baseline);
    assertEquals("", bytes.toString());
  }

  private static String plain(String text) {
    return text.replaceAll("\\x1b\\[[0-9;]*m", "");
  }

  @Test
  void streamedComparisonsPrintOneAxisAndKeepLongCaseIdentitiesDistinct() throws Exception {
    var row = BenchmarkComparisonTest.row(100);
    row.getAsJsonObject("params").addProperty("payload", "a".repeat(100));
    assertTrue(TerminalDisplay.label(row, 28).contains("Invoice.total"));
    row.addProperty("mode", "ss");
    assertTrue(TerminalDisplay.label(row, 28).contains("[ss]"));
    row.addProperty("benchmark", "bareName");
    assertTrue(TerminalDisplay.label(row, 28).contains("bareName"));
    var baseline = new BenchmarkComparison(BenchmarkComparisonTest.rows(row));
    Path stream = Files.createDirectory(directory.resolve("comparison-stream"));
    Files.writeString(stream.resolve("0.json"), row.toString());
    Files.writeString(stream.resolve("1.json"), row.toString());
    var bytes = new ByteArrayOutputStream();
    assertEquals(2, TerminalDisplay.drain(stream, 0, new PrintStream(bytes), baseline, 40));
    assertEquals(1, bytes.toString().split("vs baseline", -1).length - 1);
    TerminalDisplay.footer(
        BenchmarkComparisonTest.rows(row).toString(), new PrintStream(bytes), baseline);
    assertTrue(bytes.toString().contains("single-shot time"));
    row.remove("mode");
    TerminalDisplay.footer(
        BenchmarkComparisonTest.rows(row).toString(), new PrintStream(bytes), null);
    assertTrue(
        plain(TerminalDisplay.frame(0, 10, "a".repeat(100), 40)).replace("\r\033[2K", "").length()
            <= 40);
  }

  private ProcessBuilder child(String code) throws Exception {
    Path file =
        Files.writeString(
            directory.resolve("Child.java"),
            "class Child { public static void main(String[] args) throws Exception { "
                + code
                + " } }");
    return new ProcessBuilder(
        Path.of(
                System.getProperty("java.home"),
                "bin",
                dev.elide.smh.runtime.Platform.current().executable("java"))
            .toString(),
        file.toString());
  }

  @Test
  void animatedProcessesRetainOutputStatusAndRestoreCursor() throws Exception {
    for (int status : List.of(0, 7)) {
      var stdout = new ByteArrayOutputStream();
      var stderr = new ByteArrayOutputStream();
      assertEquals(
          status,
          TerminalDisplay.animated(
              child(
                  "System.out.print(\"[]\"); System.err.print(\"diagnostic\"); Thread.sleep(100);"
                      + " System.exit("
                      + status
                      + ");"),
              "fixture",
              true,
              new PrintStream(stdout),
              new PrintStream(stderr)));
      assertEquals("[]", stdout.toString());
      assertTrue(stderr.toString().contains("diagnostic"));
      assertTrue(stderr.toString().contains(status == 0 ? "Complete" : "Failed (exit 7)"));
      assertTrue(stderr.toString().endsWith("\033[0m\033[?25h"));
    }
    var stderr = new ByteArrayOutputStream();
    assertThrows(
        IOException.class,
        () ->
            TerminalDisplay.animated(
                new ProcessBuilder(directory.resolve("missing").toString()),
                "missing",
                false,
                System.out,
                new PrintStream(stderr)));
    assertTrue(stderr.toString().endsWith("\033[0m\033[?25h"));
    assertEquals(
        0,
        TerminalDisplay.animated(
            child(""),
            "build",
            false,
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(new ByteArrayOutputStream())));
  }

  @Test
  void forcedAndAutomaticModesLaunchTheRenderer() throws Exception {
    for (boolean results : List.of(true, false)) {
      var stdout = new ByteArrayOutputStream();
      var stderr = new ByteArrayOutputStream();
      assertEquals(
          0,
          TerminalDisplay.run(
              child("System.out.print(\"[]\");"),
              "forced",
              TerminalDisplay.Mode.tui,
              results,
              false,
              Map.of(),
              new PrintStream(stdout),
              new PrintStream(stderr)));
      assertEquals("[]", stdout.toString());
      assertTrue(stderr.toString().contains("Complete"));
    }
  }

  @Test
  void interactiveOutputRetainsTheRawReport() throws Exception {
    var stderr = new ByteArrayOutputStream();
    assertEquals(
        0,
        TerminalDisplay.run(
            child("System.out.print(\"[]\");"),
            "retained",
            TerminalDisplay.Mode.auto,
            true,
            true,
            Map.of(),
            System.out,
            new PrintStream(stderr)));
    String path =
        stderr
            .toString()
            .lines()
            .filter(line -> line.startsWith("  Raw report: "))
            .findFirst()
            .orElseThrow()
            .substring("  Raw report: ".length());
    try {
      assertEquals("[]", Files.readString(Path.of(path)));
    } finally {
      Files.delete(Path.of(path));
    }
  }

  @Test
  void interruptionTerminatesTheChildAndRestoresCursor() throws Exception {
    var stderr = new ByteArrayOutputStream();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedException.class,
          () ->
              TerminalDisplay.animated(
                  child("Thread.sleep(10000);"),
                  "interrupted",
                  false,
                  new PrintStream(new ByteArrayOutputStream()),
                  new PrintStream(stderr)));
      assertTrue(stderr.toString().endsWith("\033[0m\033[?25h"));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void buildDiagnosticsAreRetainedAndFailuresOrVerboseRunsReplayThem() throws Exception {
    var animated = new ByteArrayOutputStream();
    assertEquals(
        0,
        TerminalDisplay.build(
            child("Thread.sleep(200);"),
            "compile",
            directory,
            false,
            true,
            new PrintStream(animated)));
    assertTrue(animated.toString().contains("38;2;"));
    assertTrue(animated.toString().contains("complete"));
    PrintStream original = System.err;
    try {
      for (int status : List.of(0, 3)) {
        for (boolean verbose : List.of(false, true)) {
          var err = new ByteArrayOutputStream();
          System.setErr(new PrintStream(err));
          assertEquals(
              status,
              TerminalDisplay.build(
                  child("System.out.print(\"compiler diagnostic\"); System.exit(" + status + ");"),
                  "compile",
                  directory,
                  verbose));
          assertEquals(verbose || status != 0, err.toString().contains("compiler diagnostic"));
          assertTrue(err.toString().contains("log:"));
        }
      }
      Thread.currentThread().interrupt();
      try {
        assertThrows(
            InterruptedException.class,
            () ->
                TerminalDisplay.build(child("Thread.sleep(10000);"), "compile", directory, false));
      } finally {
        Thread.interrupted();
      }
    } finally {
      System.setErr(original);
    }
    try (var paths = Files.list(directory)) {
      assertTrue(paths.anyMatch(path -> path.toString().endsWith(".log.command.json")));
    }
  }

  @Test
  void completedCaseIsRenderedBeforeTheChildCanFinish() throws Exception {
    String row =
        "{\"benchmark\":\"stream.first\",\"params\":{},\"mode\":\"avgt\","
            + "\"vmName\":\"Substrate VM\",\"jdkVersion\":\"25\","
            + "\"primaryMetric\":{\"score\":1.5,\"scoreUnit\":\"ns/op\",\"rawData\":[[1,2]]}}";
    Path acknowledgement = directory.resolve("visible");
    var bytes = new ByteArrayOutputStream();
    var display =
        new PrintStream(bytes) {
          @Override
          public void write(byte[] buffer, int offset, int length) {
            super.write(buffer, offset, length);
            if (bytes.toString().contains("stream.first")) {
              try {
                Files.writeString(acknowledgement, "visible before exit");
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
            }
          }
        };
    String literal = new com.google.gson.Gson().toJson(row);
    String ack = new com.google.gson.Gson().toJson(acknowledgement.toString());
    var stdout = new ByteArrayOutputStream();
    assertEquals(
        0,
        TerminalDisplay.animated(
            child(
                "var folder = java.nio.file.Path.of(System.getenv(\"SMH_RESULT_STREAM\"));"
                    + "java.nio.file.Files.writeString(folder.resolve(\"pending.json\"), "
                    + literal
                    + ");java.nio.file.Files.move(folder.resolve(\"pending.json\"),"
                    + " folder.resolve(\"0.json\"));for (int i=0; i<100 &&"
                    + " !java.nio.file.Files.exists(java.nio.file.Path.of("
                    + ack
                    + ")); i++) Thread.sleep(50);"
                    + "if (!java.nio.file.Files.exists(java.nio.file.Path.of("
                    + ack
                    + "))) System.exit(9);"
                    + "System.out.print(\"[\" + "
                    + literal
                    + " + \"]\");"),
            "stream",
            true,
            new PrintStream(stdout),
            display));
    assertEquals("[" + row + "]", stdout.toString());
    assertEquals(1, bytes.toString().split("stream.first", -1).length - 1);
    assertEquals(1, bytes.toString().split("JDK 25", -1).length - 1);
    assertTrue(bytes.toString().indexOf("stream.first") < bytes.toString().indexOf("Complete"));
    assertEquals("build/log.txt", TerminalDisplay.path(Path.of("build/log.txt").toAbsolutePath()));
    TerminalDisplay.footer("not json", display, null);
  }

  @Test
  void throughputComparisonHasItsOwnDirectionAndEnvironmentIsDeduplicated() {
    var row = BenchmarkComparisonTest.row(100);
    row.addProperty("mode", "thrpt");
    row.getAsJsonObject("primaryMetric").addProperty("scoreUnit", "ops/s");
    var baseline = new BenchmarkComparison(BenchmarkComparisonTest.rows(row));
    var current = row.deepCopy();
    current.getAsJsonObject("primaryMetric").addProperty("score", 125);
    current.addProperty("vmName", "VM");
    current.addProperty("jdkVersion", "25");
    var results = new com.google.gson.JsonArray();
    results.add(current);
    results.add(current);
    var bytes = new ByteArrayOutputStream();
    TerminalDisplay.summary(results.toString(), new PrintStream(bytes), baseline);
    assertTrue(bytes.toString().contains("more throughput"));
    assertEquals(1, bytes.toString().split("JDK 25", -1).length - 1);
    assertFalse(bytes.toString().contains("Reference durations"));
  }

  @Test
  void failedStreamsRetainCompletedCasesAndForeignRootsRemainReadable() throws Exception {
    Path stream = Files.createDirectory(directory.resolve("stream"));
    Files.writeString(stream.resolve("0.json"), "{\"benchmark\":\"completed\"}");
    var bytes = new ByteArrayOutputStream();
    TerminalDisplay.finishStream(stream, false, new PrintStream(bytes));
    assertTrue(Files.readString(stream.resolve("0.json")).contains("completed"));
    assertTrue(bytes.toString().contains("Completed cases before failure:"));
    TerminalDisplay.finishStream(stream, true, new PrintStream(bytes));
    assertFalse(Files.exists(stream));
    try (var zip =
        FileSystems.newFileSystem(
            java.net.URI.create("jar:" + directory.resolve("files.zip").toUri()),
            Map.of("create", "true"))) {
      assertEquals("/log.txt", TerminalDisplay.path(zip.getPath("/log.txt")));
    }
  }
}
