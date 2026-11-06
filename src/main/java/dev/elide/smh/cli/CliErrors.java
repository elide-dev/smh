// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import picocli.CommandLine;

/** Human-readable failures and a versioned error envelope, separate from result stdout. */
final class CliErrors {
  enum Format {
    text,
    json
  }

  record Problem(String code, String title, String detail, List<String> advice) {}

  private CliErrors() {}

  static Problem describe(Exception error, boolean usage, String command) {
    String detail = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    if (usage)
      return new Problem(
          "SMH-USAGE",
          "Check the command arguments",
          detail,
          List.of("Run " + command + " --help for accepted options and examples."));
    if (detail.startsWith("Missing classpath entry: "))
      return new Problem(
          "SMH-CLASSPATH-MISSING",
          "Application dependency not found",
          detail.substring("Missing classpath entry: ".length()),
          List.of(
              "Declare prepare in the build config to build/package this JAR before each run.",
              "For an Elide project with an assemble task: elide run assemble",
              "Check classpath in the build config; paths are relative to that file."));
    if (error instanceof NoSuchFileException)
      return new Problem(
          "SMH-FILE-MISSING",
          "Required file not found",
          detail,
          List.of(
              "Check the file path and current directory.",
              "Use --config or --build-config to select an existing configuration."));
    if (error instanceof FileAlreadyExistsException)
      return new Problem(
          "SMH-OUTPUT-EXISTS",
          "Output path already exists",
          detail,
          List.of("Choose a new --output directory. Existing experiment data is not overwritten."));
    if (error instanceof AccessDeniedException)
      return new Problem(
          "SMH-PERMISSION",
          "Cannot access a required path",
          detail,
          List.of("Check file permissions, or choose a directory you can read and write."));
    if (detail.startsWith("Cannot run program"))
      return new Problem(
          "SMH-TOOL-UNAVAILABLE",
          "Could not start an external tool",
          detail,
          List.of(
              "Check the executable exists, is executable, and is available on PATH.",
              "Check javaHome, nativeImage, or --valgrind for the tool named above."));
    if (detail.startsWith("Startup timed out"))
      return new Problem(
          "SMH-STARTUP-TIMEOUT",
          "Startup exceeded its time limit",
          detail,
          List.of(
              "Inspect the retained launch stdout/stderr for blocked setup or cleanup.",
              "Fix the workload or increase --timeout (for example, --timeout 60s)."));
    if (detail.startsWith("Startup process failed"))
      return new Problem(
          "SMH-STARTUP-FAILED",
          "Candidate could not start successfully",
          detail,
          List.of(
              "Check the command, its input files, and the current working directory.",
              "For generated inputs, use --prepare before --candidate, or prepare in the build"
                  + " config.",
              "Open the launch log named above; failed launches are not retried or scored."));
    if (detail.startsWith("Preparation command failed"))
      return new Problem(
          "SMH-PREPARE-FAILED",
          "Build preparation failed",
          detail,
          List.of(
              "Open the preparation log named above and fix the reported build error.",
              "Check the preparation command and its working directory; no timed launch has"
                  + " started."));
    if (error instanceof JsonParseException)
      return new Problem(
          "SMH-CONFIG-JSON",
          "Configuration is not valid JSON",
          detail,
          List.of("Check JSON syntax and value types in the selected file."));
    if (error instanceof IllegalArgumentException)
      return new Problem(
          "SMH-CONFIG",
          "Configuration needs attention",
          detail,
          List.of(
              "Correct the setting named above, then rerun the command.",
              "Run " + command + " --help for supported options."));
    if (detail.startsWith("Build command failed"))
      return new Problem(
          "SMH-BUILD-FAILED",
          "Benchmark compilation failed",
          detail,
          List.of(
              "Open the compiler log named above and fix the first reported error.",
              "Use --verbose to print compiler diagnostics before this summary."));
    if (error instanceof IOException)
      return new Problem(
          "SMH-IO",
          "Operation could not finish",
          detail,
          List.of(
              "Inspect the diagnostic file and any build or launch logs named above.",
              "Check inputs, toolchain availability, and filesystem permissions before retrying."));
    return new Problem(
        "SMH-INTERNAL",
        "Unexpected SMH failure",
        detail,
        List.of(
            "Keep the diagnostic file and report this failure with the SMH version.",
            "Include the command and a minimal reproduction when reporting the issue."));
  }

  static int handle(Exception error, CommandLine command, boolean usage) {
    Main root = (Main) command.getCommandSpec().root().userObject();
    boolean color =
        TerminalDisplay.enabled(
            TerminalDisplay.Mode.auto,
            Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false),
            System.getenv());
    return publish(
        error,
        usage,
        command.getCommandSpec().qualifiedName(),
        root.errorFormat,
        Path.of("build", "smh-errors"),
        command.getErr(),
        color);
  }

  static int publish(
      Exception error,
      boolean usage,
      String command,
      Format format,
      Path directory,
      PrintWriter err,
      boolean color) {
    int status = usage ? 2 : 1;
    Problem problem = describe(error, usage, command);
    String diagnostics = null, logError = null;
    if (!usage) {
      try {
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "error-", ".log");
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(file))) {
          log.println(command);
          error.printStackTrace(log);
        }
        diagnostics = file.toAbsolutePath().normalize().toString();
      } catch (IOException | SecurityException failure) {
        logError = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
      }
    }
    if (format == Format.json) {
      JsonObject json = new JsonObject();
      json.addProperty("schemaVersion", 1);
      json.addProperty("type", "error");
      json.addProperty("code", problem.code());
      json.addProperty("command", command);
      json.addProperty("exitCode", status);
      json.addProperty("message", problem.title());
      json.addProperty("detail", problem.detail());
      json.add("advice", new Gson().toJsonTree(problem.advice()));
      json.add(
          "diagnostics", diagnostics == null ? JsonNull.INSTANCE : new JsonPrimitive(diagnostics));
      json.add(
          "diagnosticsError", logError == null ? JsonNull.INSTANCE : new JsonPrimitive(logError));
      err.println(new GsonBuilder().serializeNulls().create().toJson(json));
    } else {
      String red = color ? TerminalDisplay.ROSE : "",
          dim = color ? TerminalDisplay.DIM : "",
          reset = color ? TerminalDisplay.RESET : "";
      err.println("\n  " + red + "Error · " + problem.title() + reset);
      err.println("    " + TerminalDisplay.clean(problem.detail()));
      err.println("\n  Next steps");
      for (String advice : problem.advice()) err.println("    " + TerminalDisplay.clean(advice));
      err.println("\n  " + dim + "Code · " + problem.code() + reset);
      if (diagnostics != null)
        err.println(
            "  "
                + dim
                + "Details · "
                + TerminalDisplay.clean(TerminalDisplay.path(Path.of(diagnostics)))
                + reset);
      if (logError != null)
        err.println("  Could not save diagnostics · " + TerminalDisplay.clean(logError));
      err.println();
    }
    err.flush();
    return status;
  }
}
