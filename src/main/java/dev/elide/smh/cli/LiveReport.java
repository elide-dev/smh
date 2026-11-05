// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.JsonArray;
import java.io.*;

/** Snapshot consumer: transient terminal frames never become final-report scrollback. */
final class LiveReport implements AutoCloseable {
  private final PrintStream output;
  private final int width;
  private final boolean enabled;
  private final Thread cleanup;
  private boolean opened;

  LiveReport(PrintStream output, int width, boolean enabled) {
    this.output = output;
    this.width = width;
    this.enabled = enabled;
    cleanup = new Thread(this::restore, "smh-live-report-cleanup");
  }

  synchronized void update(JsonArray snapshot, String status) {
    if (!enabled) return;
    if (!opened) {
      Runtime.getRuntime().addShutdownHook(cleanup);
      opened = true;
      output.print("\033[?1049h\033[?25l");
    }
    var buffer = new ByteArrayOutputStream();
    TerminalDisplay.cards(snapshot.toString(), new PrintStream(buffer), null, width, true);
    output.print("\033[H\033[2J  " + TerminalDisplay.clean(status) + "\n");
    // Leave the last terminal row unused to prevent scrolling the live frame.
    buffer
        .toString()
        .lines()
        .limit(Math.max(1, height(System.getenv()) - 3))
        .forEach(output::println);
    output.flush();
  }

  static int height(java.util.Map<String, String> environment) {
    try {
      return Integer.parseInt(environment.getOrDefault("LINES", "24"));
    } catch (NumberFormatException ignored) {
      return 24;
    }
  }

  private synchronized void restore() {
    if (opened) {
      output.print("\033[0m\033[?25h\033[?1049l");
      output.flush();
      opened = false;
    }
  }

  @Override
  public synchronized void close() {
    if (opened) {
      restore();
      Runtime.getRuntime().removeShutdownHook(cleanup);
    }
  }
}
