// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import java.io.*;
import java.nio.file.*;

/** Retains build diagnostics even when cleaning or interruption removes their destination. */
final class BuildLog {
  private final Path staging;
  private final Path destination;
  private final PrintStream diagnostics;
  private Path retained;

  BuildLog(Path staging, Path destination, PrintStream diagnostics) {
    this.staging = staging;
    this.destination = destination;
    this.diagnostics = diagnostics;
  }

  synchronized Path publish() {
    if (retained != null) return retained;
    try {
      Files.createDirectories(destination.toAbsolutePath().getParent());
      Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
      retained = destination;
    } catch (IOException failure) {
      retained = staging;
      diagnostics.println(
          "  Log retained · "
              + TerminalDisplay.clean(TerminalDisplay.path(staging))
              + " · could not write "
              + TerminalDisplay.clean(TerminalDisplay.path(destination)));
    }
    return retained;
  }

  static void removeHook(Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException shuttingDown) {
      // The hook and normal completion share idempotent publication during shutdown.
    }
  }
}
