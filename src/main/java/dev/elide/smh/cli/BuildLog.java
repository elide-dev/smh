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
      move();
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

  /**
   * Windows can keep a terminated writer's handle open briefly after the process reports its exit,
   * so a locked log is retried for a bounded time before it is retained at its staging path.
   */
  private void move() throws IOException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    while (true) {
      try {
        Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
        return;
      } catch (NoSuchFileException missing) {
        throw missing;
      } catch (FileSystemException busy) {
        if (System.nanoTime() >= deadline) throw busy;
        java.util.concurrent.locks.LockSupport.parkNanos(20_000_000);
      }
    }
  }

  static void removeHook(Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException shuttingDown) {
      // The hook and normal completion share idempotent publication during shutdown.
    }
  }
}
