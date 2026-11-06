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
    // Direction follows the score: less time and more throughput are both faster (green).
    assertEquals(TerminalDisplay.GREEN, TerminalDisplay.deltaColor(OptionalDouble.of(-10), "avgt"));
    assertEquals(TerminalDisplay.GREEN, TerminalDisplay.deltaColor(OptionalDouble.of(10), "thrpt"));
    assertEquals(TerminalDisplay.ROSE, TerminalDisplay.deltaColor(OptionalDouble.of(10), "avgt"));
    assertEquals(TerminalDisplay.ROSE, TerminalDisplay.deltaColor(OptionalDouble.of(-10), "thrpt"));
    assertEquals(
        TerminalDisplay.DIM, TerminalDisplay.deltaColor(OptionalDouble.of(-10), "avgt", true));
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
    String text = plain(stderr.toString());
    assertTrue(text.contains("+25%"), text);
    assertTrue(text.contains("± 20%"), text);
    assertTrue(text.contains("Summary · vs baseline"), text);
    assertTrue(text.contains("1.25× slower"), text);
    assertTrue(text.contains("125 vs 100 µs/op · spread ±20%"), text);
    assertTrue(stderr.toString().contains(TerminalDisplay.ROSE + "1.25× slower"));
    stderr.reset();
    TerminalDisplay.summary(
        BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(60)).toString(),
        new PrintStream(stderr),
        baseline);
    text = plain(stderr.toString());
    assertTrue(text.contains("−40%"), text);
    assertTrue(text.contains("1.67× faster"), text);
    assertTrue(stderr.toString().contains(TerminalDisplay.GREEN + "1.67× faster"));
    stderr.reset();
    TerminalDisplay.summary(
        BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(104)).toString(),
        new PrintStream(stderr),
        baseline);
    text = plain(stderr.toString());
    assertTrue(text.contains("≈ 20%"), text);
    assertTrue(text.contains("about the same"), text);
    assertTrue(text.contains("within spread ±20%"), text);
    var throughput = BenchmarkComparisonTest.row(100);
    throughput.addProperty("mode", "thrpt");
    var throughputBaseline = new BenchmarkComparison(BenchmarkComparisonTest.rows(throughput));
    stderr.reset();
    TerminalDisplay.summary(
        BenchmarkComparisonTest.rows(BenchmarkComparisonTest.rescore(throughput, 200)).toString(),
        new PrintStream(stderr),
        throughputBaseline);
    assertTrue(plain(stderr.toString()).contains("2× faster"), stderr.toString());
    stderr.reset();
    current.get(0).getAsJsonObject().addProperty("benchmark", "unmatched");
    TerminalDisplay.summary(current.toString(), new PrintStream(stderr), baseline);
    assertTrue(stderr.toString().contains("no delta"));
    assertTrue(stderr.toString().contains("no baseline match"));
    // Zero medians on either side, infinite ratios, and rows without samples stay explicit.
    var zeroBaseline =
        new BenchmarkComparison(BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(0)));
    assertTrue(
        plain(
                TerminalDisplay.summaryBlock(
                    BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(5)), zeroBaseline))
            .contains("no baseline match"));
    assertTrue(
        plain(
                TerminalDisplay.summaryBlock(
                    BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(0)), baseline))
            .contains("no baseline match"));
    var huge =
        new BenchmarkComparison(
            BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(Double.MAX_VALUE)));
    String infinite =
        plain(
            TerminalDisplay.summaryBlock(
                BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(Double.MIN_VALUE)), huge));
    assertTrue(infinite.contains("—"), infinite);
    var scoreOnly = BenchmarkComparisonTest.row(150);
    scoreOnly.getAsJsonObject("primaryMetric").remove("rawData");
    String noSpread =
        plain(TerminalDisplay.summaryBlock(BenchmarkComparisonTest.rows(scoreOnly), baseline));
    assertTrue(noSpread.contains("1.5× slower"), noSpread);
    assertFalse(noSpread.contains("spread"), noSpread);
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
    BenchmarkComparisonTest.rescore(row, 112);
    for (int width : new int[] {40, 64, 80, 140}) {
      String rendered = plain(TerminalDisplay.comparisonRow(row, baseline, width));
      assertTrue(rendered.contains("+12%"), rendered);
      assertTrue(rendered.contains("≈ 20%"), rendered);
      assertTrue(rendered.lines().allMatch(line -> line.length() <= width), rendered);
    }
    var bytes = new ByteArrayOutputStream();
    TerminalDisplay.summary(
        BenchmarkComparisonTest.rows(row).toString(), new PrintStream(bytes), null, 80);
    assertTrue(plain(bytes.toString()).contains("Batch 64 invocations"));
    assertFalse(bytes.toString().contains("accuracy unqualified"));
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
    // Parameterized steady-state cases are separate cards, never one shared-axis overlay.
    var small = BenchmarkComparisonTest.row(100);
    var large = BenchmarkComparisonTest.rescore(BenchmarkComparisonTest.row(100), 5000);
    large.getAsJsonObject("params").addProperty("size", "1000");
    var steady = BenchmarkComparisonTest.rows(small);
    steady.add(large);
    var plainSmh = BenchmarkComparisonTest.row(100);
    plainSmh.add("smh", new com.google.gson.JsonObject());
    plainSmh.getAsJsonObject("params").addProperty("size", "plain");
    steady.add(plainSmh);
    var noSmh = BenchmarkComparisonTest.row(100);
    noSmh.remove("smh");
    noSmh.getAsJsonObject("params").addProperty("size", "foreign");
    steady.add(noSmh);
    bytes.reset();
    TerminalDisplay.summary(steady.toString(), new PrintStream(bytes), null, 80);
    String separate = plain(bytes.toString());
    assertFalse(separate.contains("shared bins"), separate);
    assertEquals(4, separate.split("  median", -1).length - 1, separate);
    bytes.reset();
    TerminalDisplay.summary(
        BenchmarkComparisonTest.rows(VariantChartsTest.row("10", 1, 2, 3)).toString(),
        new PrintStream(bytes),
        null,
        80);
    String launch = plain(bytes.toString());
    assertTrue(launch.contains("  mean"), launch);
    assertFalse(launch.contains("ms/launch  median"), launch);
    assertTrue(separate.contains("5000 µs/op  median"), separate);
    bytes.reset();
    TerminalDisplay.summary(
        BenchmarkComparisonTest.rows(ExperimentVisualsTest.row(true, false)).toString(),
        new PrintStream(bytes),
        null,
        80);
    assertTrue(plain(bytes.toString()).contains("  mean"), bytes.toString());
    var external = BenchmarkComparisonTest.row(100);
    var execution = new com.google.gson.JsonObject();
    execution.addProperty("backend", "aot");
    execution.addProperty("provenance", "external");
    external.getAsJsonObject("smh").add("execution", execution);
    bytes.reset();
    TerminalDisplay.footer(
        BenchmarkComparisonTest.rows(external).toString(), new PrintStream(bytes), null);
    assertTrue(plain(bytes.toString()).contains("external binary · build provenance unverified"));
  }

  private static String plain(String text) {
    return text.replaceAll("\\x1b\\[[0-9;]*m", "");
  }

  @Test
  void streamedComparisonsPrintOneAxisAndKeepLongCaseIdentitiesDistinct() throws Exception {
    var row = BenchmarkComparisonTest.row(100);
    row.getAsJsonObject("params").addProperty("payload", "a".repeat(100));
    assertTrue(TerminalDisplay.label(row, 28).contains("Invoice.total"));
    var unparameterized = row.deepCopy();
    unparameterized.remove("params");
    assertEquals("Invoice.total", TerminalDisplay.label(unparameterized, 28));
    assertEquals(6, TerminalDisplay.label(row, 6).length());
    assertEquals("…", TerminalDisplay.fit("abc", 1));
    assertEquals("", TerminalDisplay.fit("", 0));
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
      if (status != 0) assertRetainedOutput(stderr.toString());
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

  private void assertRetainedOutput(String diagnostics) {
    String retained =
        diagnostics
            .lines()
            .filter(line -> line.contains("Failed-run files:"))
            .findFirst()
            .orElseThrow();
    for (String file :
        retained.substring(retained.indexOf("Failed-run files:") + 17).trim().split(" · "))
      assertTrue(
          Files.isRegularFile(Path.of(file)), "Failed output must remain available: " + file);
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
      assertRetainedOutput(stderr.toString());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void terminationReapsDelegatedJvmForks() throws Exception {
    var current = dev.elide.smh.runtime.Platform.current();
    reapsDelegatedJvmForks(current);
    // Exercise the other strategy too: taskkill elsewhere, or per-process destruction on Windows.
    var windows = current.os() == dev.elide.smh.runtime.Platform.OperatingSystem.WINDOWS;
    reapsDelegatedJvmForks(
        new dev.elide.smh.runtime.Platform(
            windows
                ? dev.elide.smh.runtime.Platform.OperatingSystem.LINUX
                : dev.elide.smh.runtime.Platform.OperatingSystem.WINDOWS,
            current.architecture()));
  }

  private void reapsDelegatedJvmForks(dev.elide.smh.runtime.Platform platform) throws Exception {
    Path fork =
        Files.writeString(
            directory.resolve("Fork.java"),
            "class Fork { public static void main(String[] args) throws Exception {"
                + " Thread.sleep(30000); } }");
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                dev.elide.smh.runtime.Platform.current().executable("java"))
            .toString();
    var gson = new com.google.gson.Gson();
    Process parent =
        child(
                "var fork = new ProcessBuilder("
                    + gson.toJson(java)
                    + ", "
                    + gson.toJson(fork.toString())
                    + ").start(); System.out.println(fork.pid()); "
                    + "System.out.flush(); fork.waitFor(); Thread.sleep(30000);")
            .start();
    try {
      long pid = Long.parseLong(parent.inputReader().readLine());
      ProcessHandle descendant = ProcessHandle.of(pid).orElseThrow();
      assertTrue(descendant.isAlive());
      TerminalDisplay.terminate(parent, platform);
      assertFalse(parent.isAlive());
      assertFalse(descendant.isAlive());
    } finally {
      TerminalDisplay.terminate(parent);
    }
  }

  @Test
  void buildDiagnosticsAreRetainedAndOnlyVerboseRunsReplayThem() throws Exception {
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
          assertEquals(verbose, err.toString().contains("compiler diagnostic"));
          Path saved =
              Path.of(
                  err.toString()
                      .lines()
                      .filter(line -> line.contains("log: "))
                      .findFirst()
                      .orElseThrow()
                      .split("log: ", 2)[1]);
          assertTrue(Files.readString(saved).contains("compiler diagnostic"));
          assertTrue(err.toString().contains("log:"));
        }
      }
      var log = new ByteArrayOutputStream();
      var screen = new ByteArrayOutputStream();
      System.setErr(new PrintStream(screen));
      TerminalDisplay.BUILD_LOG.set(new PrintStream(log));
      try {
        assertEquals(
            0,
            TerminalDisplay.build(
                child("System.out.print(\"saved diagnostic\");"), "compile", directory, true));
      } finally {
        TerminalDisplay.BUILD_LOG.remove();
      }
      assertEquals("", screen.toString());
      assertTrue(log.toString().contains("saved diagnostic"));
      assertTrue(log.toString().contains("log:"));
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
    assertEquals(
        Path.of("build/log.txt").toString(),
        TerminalDisplay.path(Path.of("build/log.txt").toAbsolutePath()));
    TerminalDisplay.footer("not json", display, null);
  }

  @Test
  void liveCaseIsRenderedBeforeTheChildCanFinishAndFinalReportIsPrintedOnce() throws Exception {
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
            null,
            display));
    assertTrue(bytes.toString().contains("\033[?1049h"));
    assertTrue(bytes.toString().contains("\033[?1049l"));
    assertEquals(2, bytes.toString().split("stream.first", -1).length - 1);
    assertEquals(1, bytes.toString().split("JDK 25", -1).length - 1);
    assertTrue(bytes.toString().indexOf("stream.first") < bytes.toString().indexOf("Complete"));
    assertEquals(
        Path.of("build/log.txt").toString(),
        TerminalDisplay.path(Path.of("build/log.txt").toAbsolutePath()));
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

  @Test
  void footerDistinguishesExecutionModelsAndDiagnosticRuns() {
    var results = new com.google.gson.JsonArray();
    for (String backend : java.util.List.of("aot", "crema", "hotspot-c2")) {
      var row = BenchmarkComparisonTest.row(100);
      row.addProperty("vmName", "VM");
      row.addProperty("jdkVersion", "25");
      var execution = new com.google.gson.JsonObject();
      execution.addProperty("backend", backend);
      execution.addProperty("purpose", backend.equals("crema") ? "diagnostic" : "measurement");
      row.getAsJsonObject("smh").add("execution", execution);
      results.add(row);
    }
    var bytes = new ByteArrayOutputStream();
    TerminalDisplay.footer(results.toString(), new PrintStream(bytes), null);
    assertTrue(bytes.toString().contains("crema · VM"));
    assertTrue(bytes.toString().contains("hotspot-c2 · VM"));
    assertTrue(bytes.toString().contains("aot · VM"));
    assertTrue(bytes.toString().contains("diagnostic run"));
    assertFalse(bytes.toString().contains("measurement run"));
  }
}
