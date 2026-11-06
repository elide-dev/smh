// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class BuildLogTest {
  @TempDir Path directory;

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", Platform.current().executable("java"))
        .toString();
  }

  @Test
  void publicationIsIdempotentAndPreservesAReadableFallbackWhenBlocked() throws Exception {
    Path staging = Files.writeString(directory.resolve("staged.log"), "compiler diagnostics");
    Path destination = directory.resolve("cleaned/build.log");
    var messages = new ByteArrayOutputStream();
    var log = new BuildLog(staging, destination, new PrintStream(messages));
    assertEquals(destination, log.publish());
    assertEquals(destination, log.publish());
    assertFalse(Files.exists(staging));
    assertEquals("compiler diagnostics", Files.readString(destination));
    assertEquals("", messages.toString());

    Files.writeString(staging, "failure details");
    Path blocked = Files.writeString(directory.resolve("blocked"), "not a directory");
    var fallback = new BuildLog(staging, blocked.resolve("build.log"), new PrintStream(messages));
    assertEquals(staging, fallback.publish());
    assertEquals(staging, fallback.publish());
    assertEquals("failure details", Files.readString(staging));
    assertTrue(messages.toString().contains(TerminalDisplay.path(staging)));
    assertTrue(messages.toString().contains("could not write"));
  }

  @Test
  void persistentMoveFailuresAreRetriedThenRetainedAndMissingLogsAreNot() throws Exception {
    Path staging = Files.writeString(directory.resolve("locked.log"), "locked diagnostics");
    // A non-empty destination directory fails every move, standing in for a still-open writer.
    Path occupied = Files.createDirectories(directory.resolve("occupied"));
    Files.writeString(occupied.resolve("entry"), "entry");
    var messages = new ByteArrayOutputStream();
    long started = System.nanoTime();
    assertEquals(staging, new BuildLog(staging, occupied, new PrintStream(messages)).publish());
    assertTrue(System.nanoTime() - started >= 1_000_000_000L);
    assertEquals("locked diagnostics", Files.readString(staging));
    assertTrue(messages.toString().contains("could not write"));

    Path missing = directory.resolve("missing.log");
    started = System.nanoTime();
    assertEquals(
        missing,
        new BuildLog(missing, directory.resolve("moved.log"), new PrintStream(messages)).publish());
    assertTrue(System.nanoTime() - started < 1_000_000_000L);
  }

  @Test
  void scopedCleanupTerminatesChildBeforePublishingOutputAndCommand() throws Exception {
    Path source =
        Files.writeString(
            directory.resolve("Waiting.java"),
            "class Waiting { public static void main(String[] a) throws Exception {"
                + " Thread.sleep(60000); } }");
    Path staging = Files.writeString(directory.resolve("pending.log"), "before interruption");
    Path target = directory.resolve("retained/compiler.log");
    Path command = Files.writeString(directory.resolve("pending.json"), "[\"java\"]");
    var cleanup =
        new TerminalDisplay.BuildCleanup(
            new BuildLog(staging, target, System.err),
            new BuildLog(command, Path.of(target + ".command.json"), System.err));
    var scope = new TerminalDisplay.BuildScope();
    scope.finishBuild();
    scope.cleanup = cleanup;
    Process child =
        cleanup.start(
            new ProcessBuilder(java(), source.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(staging.toFile())));
    try {
      assertTrue(child.isAlive());
      scope.finishBuild();
      assertFalse(child.isAlive());
      assertTrue(Files.readString(target).contains("before interruption"));
      assertEquals("[\"java\"]", Files.readString(Path.of(target + ".command.json")));
      scope.finishBuild();
      assertThrows(IOException.class, () -> cleanup.start(new ProcessBuilder(java(), "-version")));
    } finally {
      cleanup.run();
    }
    Thread hook = new Thread(scope::finishBuild);
    Runtime.getRuntime().addShutdownHook(hook);
    BuildLog.removeHook(hook);
  }

  @Test
  void blockedPublicationDoesNotReplaceTheOriginalChildFailure() throws Exception {
    Path logs = directory.resolve("logs");
    Path source =
        Files.writeString(
            directory.resolve("Failure.java"),
            """
            import java.nio.file.*;
            class Failure {
              public static void main(String[] args) throws Exception {
                System.err.println("original build failure");
                Path logs = Path.of(args[0]);
                Files.delete(logs);
                Files.writeString(logs, "blocked destination");
                System.exit(7);
              }
            }
            """);
    var messages = new ByteArrayOutputStream();
    PrintStream original = System.err;
    try (var captured = new PrintStream(messages)) {
      System.setErr(captured);
      List<String> argv = List.of(java(), source.toString(), logs.toString());
      assertEquals(
          7,
          TerminalDisplay.build(new ProcessBuilder(argv), "Prepare", logs, true, false, captured));
      assertTrue(messages.toString().contains("original build failure"));
      List<Path> retained =
          messages
              .toString()
              .lines()
              .filter(line -> line.startsWith("  Log retained · "))
              .map(line -> line.substring("  Log retained · ".length()).split(" · ")[0])
              .map(Path::of)
              .toList();
      assertEquals(2, retained.size());
      assertEquals("original build failure", Files.readString(retained.getFirst()).strip());
      assertEquals(new Gson().toJson(argv), Files.readString(retained.getLast()));
      for (Path file : retained) Files.delete(file);
    } finally {
      System.setErr(original);
    }
  }
}
