// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Project preparation runs once before validation, cache lookup, and measured launches. */
final class BuildPreparation {
  private BuildPreparation() {}

  static void validate(List<String> command) {
    if (command.isEmpty() || command.getFirst().isBlank())
      throw new IllegalArgumentException("prepare requires a nonempty executable/argument array");
  }

  static void prepare(BuildConfig config, boolean dryRun) throws Exception {
    if (config.prepare.isEmpty()) return;
    validate(config.prepare);
    if (dryRun) {
      TerminalDisplay.buildMessages()
          .println(
              "  Prepare · "
                  + new Gson().toJson(config.prepare)
                  + " · cwd: "
                  + TerminalDisplay.path(config.directory));
      return;
    }
    run(config.prepare, config.directory, config.output.resolve("prepare"), config.verbose);
  }

  static void run(List<String> command, Path cwd, Path logs, boolean verbose) throws Exception {
    validate(command);
    ProcessBuilder process = new ProcessBuilder(command).directory(cwd.toFile());
    int status = TerminalDisplay.build(process, "Prepare", logs, verbose);
    if (status != 0)
      throw new IOException(
          "Preparation command failed (exit " + status + "); logs: " + logs.toAbsolutePath());
  }
}
