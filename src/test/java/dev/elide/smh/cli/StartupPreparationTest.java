// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("portable")
class StartupPreparationTest {
  @TempDir Path directory;

  private record Output(int status, String stdout, String stderr) {}

  private Output invoke(List<String> arguments) {
    PrintStream out = System.out, err = System.err;
    ByteArrayOutputStream stdout = new ByteArrayOutputStream(),
        stderr = new ByteArrayOutputStream();
    try (PrintStream captureOut = new PrintStream(stdout);
        PrintStream captureErr = new PrintStream(stderr)) {
      System.setOut(captureOut);
      System.setErr(captureErr);
      return new Output(
          Main.execute(arguments.toArray(String[]::new)), stdout.toString(), stderr.toString());
    } finally {
      System.setOut(out);
      System.setErr(err);
    }
  }

  private Path fixture() throws Exception {
    Files.createDirectory(directory.resolve("state with spaces"));
    return Files.writeString(
        directory.resolve("LaunchFixture.java"),
        """
        import java.nio.file.*;
        class LaunchFixture {
          public static void main(String[] args) throws Exception {
            if (!"flag value".equals(System.getProperty("fixture.option"))
                || !args[2].equals("--flag") || !args[3].equals("two words"))
              throw new IllegalArgumentException("argv was changed");
            String mode = args[0];
            Path state = Path.of(args[1]);
            Files.writeString(state.resolve("events.txt"), mode + "\\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(state.resolve(mode + ".cwd"), Path.of("").toAbsolutePath().toString());
            if (mode.startsWith("prepare")) {
              // A clean build may delete the experiment directory already reserved by SMH.
              Path report = state.getParent().resolve("report");
              if (Files.exists(report)) {
                try (var files = Files.walk(report)) {
                  for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(file);
                }
              }
              if (System.in.read() != -1) throw new IllegalStateException("stdin must be closed");
              System.out.println("preparation stdout " + mode);
              System.err.println("preparation stderr " + mode);
              if (mode.equals("prepare-fail")) System.exit(7);
              if (mode.equals("prepare-first")) Files.writeString(state.resolve("first"), "built");
              else {
                if (!Files.readString(state.resolve("first")).equals("built"))
                  throw new IllegalStateException("first preparation has not finished");
                Files.writeString(state.resolve("second"), "built");
                if (mode.equals("prepare-block")) Files.createDirectories(report.resolve("run.json"));
              }
            } else {
              if (!Files.readString(state.resolve("second")).equals("built"))
                throw new IllegalStateException("preparation has not finished");
              System.out.println("candidate stdout " + mode);
              if (mode.equals("candidate-fail")) {
                System.err.println("deliberate candidate failure");
                System.exit(9);
              }
            }
          }
        }
        """);
  }

  private List<String> command(Path fixture, String mode) {
    return List.of(
        Path.of(System.getProperty("java.home"), "bin", Platform.current().executable("java"))
            .toString(),
        "-Dfixture.option=flag value",
        fixture.toString(),
        mode,
        directory.resolve("state with spaces").toString(),
        "--flag",
        "two words");
  }

  private List<String> arguments(Path output, String... groups) throws Exception {
    Path fixture = fixture();
    List<String> arguments =
        new ArrayList<>(
            List.of(
                "--mode",
                "startup",
                "--launches",
                "2",
                "--timeout",
                "20s",
                "--display",
                "plain",
                "--output",
                output.toString()));
    for (String group : groups) {
      arguments.add(group.startsWith("prepare") ? "--prepare" : "--candidate");
      arguments.addAll(command(fixture, group));
    }
    return arguments;
  }

  private String events() throws Exception {
    return Files.readString(directory.resolve("state with spaces/events.txt"));
  }

  @Test
  void repeatedPreparationAndCandidateOptionsPreserveArgvAndBoundaries() {
    Main root = new Main();
    new CommandLine(root)
        .parseArgs(
            "--mode",
            "startup",
            "--prepare",
            "javac",
            "--release",
            "25",
            "a file.java",
            "--candidate",
            "java",
            "-Xmx64m",
            "Main",
            "--app-flag",
            "--prepare",
            "elide",
            "run",
            "assemble",
            "--verbose",
            "--candidate",
            "bun",
            "--smol",
            "file name.js");
    assertEquals(
        List.of(
            "[\"javac\",\"--release\",\"25\",\"a file.java\"]",
            "[\"elide\",\"run\",\"assemble\",\"--verbose\"]"),
        root.preparations);
    assertEquals(
        List.of(
            "[\"java\",\"-Xmx64m\",\"Main\",\"--app-flag\"]",
            "[\"bun\",\"--smol\",\"file name.js\"]"),
        root.candidates);
    for (String option : List.of("--prepare", "--candidate")) {
      var missing =
          assertThrows(
              CommandLine.ParameterException.class,
              () -> new CommandLine(new Main()).parseArgs(option));
      assertTrue(missing.getMessage().contains(option));
      assertThrows(
          CommandLine.ParameterException.class,
          () -> new CommandLine(new Main()).parseArgs(option, "--candidate", "java"));
    }
  }

  @Test
  void preparationRequiresCandidatesAndIsNotExecutedForInvalidComparison() throws Exception {
    Path fixture = fixture();
    List<String> arguments = new ArrayList<>(List.of("--prepare"));
    arguments.addAll(command(fixture, "prepare-first"));
    Output noCandidates = invoke(arguments);
    assertEquals(1, noCandidates.status);
    assertEquals("", noCandidates.stdout);
    assertTrue(noCandidates.stderr.contains("--prepare requires --candidate"));
    assertFalse(Files.exists(directory.resolve("state with spaces/events.txt")));
    ExperimentOptions options = new ExperimentOptions();
    options.mode = "startup";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StartupCandidates.run(
                List.of(new Gson().toJson(command(fixture, "candidate-a"))),
                options,
                TerminalDisplay.Mode.plain,
                false,
                List.of(new Gson().toJson(command(fixture, "prepare-first")))));
    assertFalse(Files.exists(directory.resolve("state with spaces/events.txt")));
  }

  @Test
  void allPreparationCompletesOnceBeforeInterleavedLaunchesAndOnlyResultsReachStdout()
      throws Exception {
    Path output = directory.resolve("report");
    Output result =
        invoke(arguments(output, "prepare-first", "candidate-a", "prepare-second", "candidate-b"));
    assertEquals(0, result.status, result.stderr);
    JsonArray rows = JsonParser.parseString(result.stdout).getAsJsonArray();
    assertEquals(2, rows.size());
    assertEquals(
        "prepare-first\nprepare-second\ncandidate-a\ncandidate-b\ncandidate-b\ncandidate-a\n",
        events());
    JsonArray preparation =
        JsonParser.parseString(Files.readString(output.resolve("prepare.json"))).getAsJsonArray();
    assertEquals(2, preparation.size());
    assertFalse(result.stderr.contains("preparation stdout"));
    assertFalse(result.stderr.contains("candidate stdout"));
    for (int i = 0; i < 2; i++) {
      JsonObject row = rows.get(i).getAsJsonObject();
      assertEquals(2, row.get("forks").getAsInt());
      assertEquals(2, row.getAsJsonObject("smh").getAsJsonArray("observations").size());
      assertEquals(
          2,
          row.getAsJsonObject("primaryMetric")
              .getAsJsonArray("rawData")
              .get(0)
              .getAsJsonArray()
              .size());
      try (var files = Files.list(output.resolve("prepare-" + i))) {
        var logs = files.toList();
        assertEquals(2, logs.size());
        for (Path log : logs) {
          if (log.toString().endsWith(".command.json"))
            assertEquals(preparation.get(i), JsonParser.parseString(Files.readString(log)));
          else assertTrue(Files.readString(log).contains("preparation stdout"));
        }
      }
      assertTrue(
          Files.readString(output.resolve("candidate-" + i + "/launch-0/stdout.log"))
              .contains("candidate stdout"));
    }
    assertEquals(
        Path.of("").toAbsolutePath().toString(),
        Files.readString(directory.resolve("state with spaces/prepare-first.cwd")));
    assertEquals(rows, JsonParser.parseString(Files.readString(output.resolve("results.json"))));
  }

  @Test
  void failedPreparationStopsBeforeFurtherPreparationOrAnyMeasuredLaunch() throws Exception {
    Path output = directory.resolve("failed-prepare");
    Output result =
        invoke(arguments(output, "prepare-fail", "prepare-second", "candidate-a", "candidate-b"));
    assertEquals(1, result.status);
    assertEquals("", result.stdout);
    assertTrue(result.stderr.contains("SMH-PREPARE-FAILED"), result.stderr);
    assertEquals("prepare-fail\n", events());
    assertTrue(Files.isRegularFile(output.resolve("prepare.json")));
    assertTrue(Files.isDirectory(output.resolve("prepare-0")));
    assertFalse(Files.exists(output.resolve("prepare-1")));
    assertFalse(Files.exists(output.resolve("candidate-0")));
    assertFalse(Files.exists(output.resolve("results.json")));
  }

  @Test
  void laterFailedCleanPreparationPreservesEarlierLogsAndMetadata() throws Exception {
    Path output = directory.resolve("report");
    Output result =
        invoke(arguments(output, "prepare-first", "prepare-fail", "candidate-a", "candidate-b"));
    assertEquals(1, result.status);
    assertEquals("", result.stdout);
    assertTrue(result.stderr.contains("SMH-PREPARE-FAILED"), result.stderr);
    assertEquals("prepare-first\nprepare-fail\n", events());
    for (int i = 0; i < 2; i++) {
      try (var files = Files.list(output.resolve("prepare-" + i))) {
        var retained = files.toList();
        assertEquals(2, retained.size());
        for (Path file : retained)
          if (file.toString().endsWith(".log"))
            assertTrue(Files.readString(file).contains(i == 0 ? "prepare-first" : "prepare-fail"));
      }
    }
    assertEquals(
        2,
        JsonParser.parseString(Files.readString(output.resolve("prepare.json")))
            .getAsJsonArray()
            .size());
    assertTrue(Files.isRegularFile(output.resolve("commands.json")));
    assertFalse(Files.exists(output.resolve("candidate-0")));
    assertFalse(Files.exists(output.resolve("results.json")));
    assertNull(TerminalDisplay.BUILD_SCOPE.get());
  }

  @Test
  void shutdownRestoresCompletedLogsAndKeepsArchiveIfDestinationIsUnavailable() throws Exception {
    Path report = directory.resolve("report");
    Path logs = Files.createDirectories(report.resolve("prepare-0"));
    Path original = Files.writeString(logs.resolve("compiler.log"), "retained build output");
    var prepared =
        new StartupCandidates.PreparationFiles(
            report, new JsonObject(), List.of("[\"app\"]"), List.of("[\"builder\"]"));
    prepared.retain(logs);
    Files.delete(original);
    Files.delete(logs);
    Files.delete(report);
    var scope = new TerminalDisplay.BuildScope();
    Files.writeString(report, "destination is blocked");
    PrintStream saved = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (PrintStream error = new PrintStream(captured)) {
      System.setErr(error);
      prepared.shutdown(scope);
    } finally {
      System.setErr(saved);
    }
    assertTrue(captured.toString().contains("Preparation logs retained"));
    assertTrue(Files.isDirectory(prepared.staging));
    assertTrue(
        assertThrows(IOException.class, prepared::publish)
            .getMessage()
            .contains(prepared.staging.toString()));
    Files.delete(report);
    prepared.publish();
    assertTrue(prepared.published);
    assertEquals("[[\"builder\"]]", Files.readString(report.resolve("prepare.json")));
    prepared.discard();
    assertFalse(Files.exists(prepared.staging));
    assertEquals("retained build output", Files.readString(original));
    prepared.shutdown(scope);
    assertEquals("retained build output", Files.readString(original));
  }

  @Test
  void metadataPublicationFailureStopsBeforeMeasurementAndRetainsPreparationArchive()
      throws Exception {
    Path output = directory.resolve("report");
    Output result =
        invoke(arguments(output, "prepare-first", "prepare-block", "candidate-a", "candidate-b"));
    assertEquals(1, result.status);
    assertEquals("", result.stdout);
    assertTrue(result.stderr.contains("Could not publish preparation metadata"), result.stderr);
    assertEquals("prepare-first\nprepare-block\n", events());
    assertFalse(Files.exists(output.resolve("candidate-0")));
    assertFalse(Files.exists(output.resolve("results.json")));
    String marker = "logs retained at ";
    String retained =
        result
            .stderr
            .substring(result.stderr.indexOf(marker) + marker.length())
            .lines()
            .findFirst()
            .orElseThrow()
            .strip();
    Path staging = Path.of(retained);
    assertTrue(Files.isDirectory(staging), result.stderr);
    try (var files = Files.list(staging)) {
      var paths = files.toList();
      assertEquals(4, paths.size());
      for (Path path : paths) Files.delete(path);
    }
    Files.delete(staging);
    assertNull(TerminalDisplay.BUILD_SCOPE.get());
  }

  @Test
  void failedCandidateIsNotRetriedAndDoesNotTriggerPreparationAgain() throws Exception {
    Path output = directory.resolve("failed-candidate");
    Output result =
        invoke(
            arguments(output, "prepare-first", "prepare-second", "candidate-fail", "candidate-b"));
    assertEquals(1, result.status);
    assertEquals("", result.stdout);
    assertTrue(result.stderr.contains("SMH-STARTUP-FAILED"), result.stderr);
    assertTrue(result.stderr.contains("exit 9"), result.stderr);
    assertEquals("prepare-first\nprepare-second\ncandidate-fail\n", events());
    assertTrue(Files.isRegularFile(output.resolve("candidate-0/launch-0/failure.json")));
    assertFalse(Files.exists(output.resolve("candidate-0/launch-0/observation.json")));
    assertFalse(Files.exists(output.resolve("candidate-0/launch-1")));
    assertFalse(Files.exists(output.resolve("candidate-1")));
    assertFalse(Files.exists(output.resolve("results.json")));
  }
}
