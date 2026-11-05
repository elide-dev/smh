// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class CliErrorsTest {
  @TempDir Path directory;

  @Test
  void commonFailuresHaveStableCodesAndSpecificRecoveryAdvice() {
    Map<Exception, String> cases = new LinkedHashMap<>();
    cases.put(
        new IllegalArgumentException("Missing classpath entry: app.jar"), "SMH-CLASSPATH-MISSING");
    cases.put(new NoSuchFileException("run.json"), "SMH-FILE-MISSING");
    cases.put(new FileAlreadyExistsException("results"), "SMH-OUTPUT-EXISTS");
    cases.put(new AccessDeniedException("results"), "SMH-PERMISSION");
    cases.put(new IOException("Cannot run program native-image"), "SMH-TOOL-UNAVAILABLE");
    cases.put(new IOException("Startup timed out; see launch-1"), "SMH-STARTUP-TIMEOUT");
    cases.put(new JsonSyntaxException("Malformed object"), "SMH-CONFIG-JSON");
    cases.put(new IllegalArgumentException("duration must be positive"), "SMH-CONFIG");
    cases.put(new IOException("compiler failed"), "SMH-IO");
    cases.put(new IOException("Build command failed (exit 1): native-image"), "SMH-BUILD-FAILED");
    cases.put(new IllegalStateException(), "SMH-INTERNAL");
    for (var entry : cases.entrySet()) {
      var problem = CliErrors.describe(entry.getKey(), false, "smh bench");
      assertEquals(entry.getValue(), problem.code());
      assertFalse(problem.advice().isEmpty());
      assertFalse(problem.detail().isBlank());
    }
    assertTrue(
        CliErrors.describe(cases.keySet().iterator().next(), false, "smh build").advice().stream()
            .anyMatch(s -> s.contains("elide run assemble")));
    assertEquals(
        "SMH-USAGE",
        CliErrors.describe(new Exception("--config required"), true, "smh build").code());
  }

  @Test
  void textIsSafeAndReadableWhileLogsRetainTheFullCauseChain() throws Exception {
    Exception error =
        new IllegalArgumentException("bad\033[31m\nsetting", new IOException("root cause"));
    for (boolean color : List.of(false, true)) {
      var text = new StringWriter();
      assertEquals(
          1,
          CliErrors.publish(
              error,
              false,
              "smh bench",
              CliErrors.Format.text,
              directory,
              new PrintWriter(text),
              color));
      String output = text.toString();
      assertTrue(output.contains("Next steps"));
      assertTrue(output.contains("SMH-CONFIG"));
      assertFalse(output.contains("java.lang.IllegalArgumentException"));
      assertFalse(output.contains("\033[31m"));
      assertEquals(color, output.contains(TerminalDisplay.ROSE));
      assertTrue(output.lines().filter(s -> !s.isBlank()).allMatch(s -> s.startsWith("  ")));
      Path log =
          Path.of(
              output
                  .lines()
                  .filter(s -> s.contains("Details ·"))
                  .findFirst()
                  .orElseThrow()
                  .replaceAll("\033\\[[0-9;]*m", "")
                  .split("Details · ", 2)[1]);
      assertTrue(Files.readString(log).contains("Caused by: java.io.IOException: root cause"));
    }
  }

  @Test
  void jsonPreservesDetailsAndReportsDiagnosticWriteFailuresWithoutHidingTheError()
      throws Exception {
    for (boolean usage : List.of(false, true)) {
      var text = new StringWriter();
      int status =
          CliErrors.publish(
              new IllegalArgumentException("bad\nvalue"),
              usage,
              "smh bench",
              CliErrors.Format.json,
              directory,
              new PrintWriter(text),
              true);
      JsonObject error = JsonParser.parseString(text.toString()).getAsJsonObject();
      assertEquals(usage ? 2 : 1, status);
      assertEquals(status, error.get("exitCode").getAsInt());
      assertEquals(1, error.get("schemaVersion").getAsInt());
      assertEquals("error", error.get("type").getAsString());
      assertEquals("bad\nvalue", error.get("detail").getAsString());
      assertFalse(error.getAsJsonArray("advice").isEmpty());
      assertEquals(usage, error.get("diagnostics").isJsonNull());
      assertTrue(error.get("diagnosticsError").isJsonNull());
    }
    Path blocked = Files.writeString(directory.resolve("not-a-directory"), "file");
    for (var format : CliErrors.Format.values()) {
      var text = new StringWriter();
      assertEquals(
          1,
          CliErrors.publish(
              new Exception("original failure"),
              false,
              "smh build",
              format,
              blocked,
              new PrintWriter(text),
              false));
      assertTrue(text.toString().contains("original failure"));
      if (format == CliErrors.Format.json) {
        JsonObject error = JsonParser.parseString(text.toString()).getAsJsonObject();
        assertTrue(error.get("diagnostics").isJsonNull());
        assertFalse(error.get("diagnosticsError").isJsonNull());
      } else assertTrue(text.toString().contains("Could not save diagnostics"));
    }
    var text = new StringWriter();
    CliErrors.publish(
        new Exception("unknown flag"),
        true,
        "smh",
        CliErrors.Format.text,
        blocked,
        new PrintWriter(text),
        false);
    assertFalse(text.toString().contains("Details ·"));
  }
}
