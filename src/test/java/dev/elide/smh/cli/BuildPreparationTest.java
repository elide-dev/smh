// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class BuildPreparationTest {
  @TempDir Path directory;

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", Platform.current().executable("java"))
        .toString();
  }

  private BuildConfig config() throws Exception {
    Path sdk = Files.createDirectories(directory.resolve("sdk"));
    Files.writeString(sdk.resolve("smh.jar"), "processor");
    Files.writeString(sdk.resolve("smh-runtime.jar"), "runtime");
    Files.createDirectory(sdk.resolve("lib"));
    Files.writeString(directory.resolve("Work.java"), "class Work {}");
    Files.writeString(directory.resolve("application.txt"), "original");
    Files.writeString(
        directory.resolve("Prepare.java"),
        """
        import java.nio.file.*;
        import java.util.*;
        class Prepare {
          public static void main(String[] args) throws Exception {
            if (System.in.read() != -1) throw new IllegalStateException("stdin must be closed");
            Files.writeString(Path.of("calls.txt"), "call\\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("preparation stdout");
            System.err.println("preparation stderr");
            if (args[0].startsWith("clean")) {
              Path output = Path.of("output");
              if (Files.exists(output.resolve("report/run.json")))
                throw new IllegalStateException("run config must be published after preparation");
              try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
              }
              if (args[0].equals("clean-fail")) System.exit(7);
            }
            if (args[0].equals("fail")) System.exit(7);
            Files.writeString(Path.of("generated.jar"), Files.readString(Path.of("application.txt")));
            Files.writeString(Path.of("args.txt"), Arrays.toString(args));
          }
        }
        """);
    JsonObject json = new JsonObject();
    json.addProperty("home", "sdk");
    json.addProperty("output", "output");
    json.add("sources", new Gson().toJsonTree(List.of("Work.java")));
    json.add("classpath", new Gson().toJsonTree(List.of("generated.jar")));
    json.add(
        "prepare", new Gson().toJsonTree(List.of(java(), "Prepare.java", "ok", "two words", "")));
    Path file = Files.writeString(directory.resolve("build.json"), json.toString());
    return BuildConfig.read(file, null);
  }

  @Test
  void preparationRepairsMissingInputsBeforeCacheLookupAndReusesUnchangedArtifacts()
      throws Exception {
    BuildConfig c = config();
    assertEquals(directory, c.directory);
    assertThrows(IllegalArgumentException.class, c::validate);
    AtomicInteger compilations = new AtomicInteger();
    BuildCache.Compiler compiler =
        settings -> {
          compilations.incrementAndGet();
          return Files.writeString(settings.output.resolve("binary"), "compiled");
        };
    BuildCache.Identity identity =
        settings -> BuildCache.Fingerprint.of(BuildCache.hash(settings.classpath.getFirst()));
    BuildPreparation.prepare(c, false);
    c.validate();
    var first = BuildCache.build(c, false, compiler, identity);
    assertFalse(first.hit());
    BuildPreparation.prepare(c, false);
    assertTrue(BuildCache.build(c, false, compiler, identity).hit());
    Files.writeString(directory.resolve("application.txt"), "changed");
    BuildPreparation.prepare(c, false);
    var changed = BuildCache.build(c, false, compiler, identity);
    assertFalse(changed.hit());
    assertNotEquals(first.key(), changed.key());
    assertEquals(2, compilations.get());
    assertEquals("call\ncall\ncall\n", Files.readString(directory.resolve("calls.txt")));
    assertEquals("[ok, two words, ]", Files.readString(directory.resolve("args.txt")));
    try (var files = Files.list(c.output.resolve("prepare"))) {
      for (Path file : files.toList()) {
        if (file.toString().endsWith(".command.json"))
          assertEquals(
              new Gson().toJsonTree(c.prepare), JsonParser.parseString(Files.readString(file)));
        else
          assertEquals(
              "preparation stdout\npreparation stderr\n",
              Files.readString(file).replace("\r\n", "\n"));
      }
    }
  }

  @Test
  void failedPreparationKeepsLogsAndCannotBecomeACacheHit() throws Exception {
    BuildConfig c = config();
    c.prepare.set(2, "fail");
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (PrintStream log = new PrintStream(captured)) {
      TerminalDisplay.BUILD_LOG.set(log);
      IOException failure =
          assertThrows(IOException.class, () -> BuildPreparation.prepare(c, false));
      assertTrue(failure.getMessage().contains("Preparation command failed (exit 7)"));
      assertTrue(failure.getMessage().contains(c.output.resolve("prepare").toString()));
    } finally {
      TerminalDisplay.BUILD_LOG.remove();
    }
    assertTrue(captured.toString().contains("Prepare · failed"));
    assertFalse(captured.toString().contains("preparation stderr"));
    assertFalse(Files.exists(directory.resolve("generated.jar")));
    assertFalse(Files.exists(c.output.resolve(".smh-cache")));
    c.prepare.set(2, "ok");
    c.verbose = true;
    try (PrintStream log = new PrintStream(captured)) {
      TerminalDisplay.BUILD_LOG.set(log);
      BuildPreparation.prepare(c, false);
    } finally {
      TerminalDisplay.BUILD_LOG.remove();
    }
    assertTrue(captured.toString().contains("preparation stdout"));
  }

  @Test
  void dryRunPrintsPreparationWithoutRunningItAndKeepsOtherValidationStrict() throws Exception {
    BuildConfig c = config();
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (PrintStream log = new PrintStream(captured)) {
      TerminalDisplay.BUILD_LOG.set(log);
      BuildPreparation.prepare(c, true);
      c.validate(false);
    } finally {
      TerminalDisplay.BUILD_LOG.remove();
    }
    assertTrue(captured.toString().contains("Prepare · ["));
    assertTrue(captured.toString().contains("cwd: " + TerminalDisplay.path(directory)));
    assertFalse(Files.exists(directory.resolve("calls.txt")));
    assertFalse(Files.exists(c.output));
    c.pgo = "use";
    c.profile = directory.resolve("missing.iprof");
    assertThrows(IllegalArgumentException.class, () -> c.validate(false));
    c.prepare.clear();
    BuildPreparation.prepare(c, false);
    assertFalse(Files.exists(c.output));
  }

  @Test
  void buildCommandPreparesOnceAndDryRunCanPlanMissingGeneratedClasspath() throws Exception {
    BuildConfig c = config();
    Main.Build command = new Main.Build();
    command.config = directory.resolve("build.json");
    command.builder =
        new AotBuilder() {
          @Override
          public Path build(BuildConfig settings, boolean dryRun) throws Exception {
            assertFalse(dryRun);
            settings.validate();
            assertEquals("call\n", Files.readString(directory.resolve("calls.txt")));
            return settings.output.resolve("planned-benchmark");
          }
        };
    assertEquals(0, command.call());
    Files.delete(directory.resolve("generated.jar"));
    command.dryRun = true;
    command.bytecodeOnly = true;
    assertEquals(0, command.call());
    assertEquals("call\n", Files.readString(directory.resolve("calls.txt")));
    c.nativeImage = List.of(java());
    c.layers = false;
    new AotBuilder((arguments, cwd) -> fail("Dry-run must not execute compiler commands"))
        .build(c, true);
    assertFalse(Files.exists(directory.resolve("generated.jar")));
    assertFalse(Files.exists(c.output.resolve("build.json")));
    c.prepare.clear();
    assertThrows(IllegalArgumentException.class, () -> new AotBuilder().build(c, true));
  }

  @Test
  void cleaningPreparationCannotDeleteTheBenchTranscriptOrEffectiveRunConfiguration()
      throws Exception {
    config();
    Path build = directory.resolve("build.json");
    JsonObject settings = JsonParser.parseString(Files.readString(build)).getAsJsonObject();
    Path originalRun = Files.writeString(directory.resolve("run.json"), "{}");
    for (String mode : List.of("clean", "clean-fail")) {
      Path report = directory.resolve("output/report");
      if (Files.exists(report)) {
        try (var paths = Files.walk(report)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
      }
      settings.getAsJsonArray("prepare").set(2, new JsonPrimitive(mode));
      Files.writeString(build, settings.toString());
      Main.Bench bench = new Main.Bench();
      bench.buildConfig = build;
      bench.config = originalRun;
      bench.execution = "crema";
      bench.display = TerminalDisplay.Mode.plain;
      bench.experiment.mode = "startup";
      bench.experiment.output = report;
      IOException failure = assertThrows(IOException.class, bench::call);
      assertTrue(
          failure
              .getMessage()
              .contains(
                  mode.equals("clean")
                      ? "Missing Crema host manifest"
                      : "Preparation command failed"),
          failure.getMessage());
      assertTrue(Files.readString(report.resolve("build.log")).contains("Prepare ·"));
      assertTrue(Files.isDirectory(directory.resolve("output/prepare")));
      if (mode.equals("clean")) {
        assertEquals("{}", Files.readString(report.resolve("requested-run.json")));
        assertEquals(
            "startup",
            JsonParser.parseString(Files.readString(report.resolve("run.json")))
                .getAsJsonObject()
                .get("mode")
                .getAsString());
      } else assertFalse(Files.exists(report.resolve("run.json")));
      assertNull(TerminalDisplay.BUILD_LOG.get());
    }
  }

  @Test
  void preparationRejectsAmbiguousOrInvalidCommands() throws Exception {
    for (String invalid :
        List.of(
            "[]",
            "[\"\"]",
            "[\"  \"]",
            "\"elide run assemble\"",
            "null",
            "[true]",
            "[1]",
            "[null]",
            "[{}]")) {
      Path file =
          Files.writeString(
              directory.resolve("invalid.json"),
              "{\"sources\":[\"Work.java\"],\"prepare\":" + invalid + "}");
      assertThrows(IllegalArgumentException.class, () -> BuildConfig.read(file, null), invalid);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildPreparation.run(List.of(), directory, directory, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildPreparation.run(List.of(" "), directory, directory, false));
  }
}
