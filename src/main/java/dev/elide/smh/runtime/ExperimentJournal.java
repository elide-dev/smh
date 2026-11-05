// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Append completed soak windows outside timed work; partial journals are never result reports. */
final class ExperimentJournal {
  private ExperimentJournal() {}

  static void record(
      RunConfig config, String benchmark, Map<String, String> params, JsonObject observation)
      throws java.io.IOException {
    if (!config.mode.equals("soak") || config.output == null) return;
    Path path = Path.of(config.output.toAbsolutePath() + ".progress.jsonl");
    Files.createDirectories(path.getParent());
    JsonObject entry = new JsonObject();
    entry.addProperty("benchmark", benchmark);
    entry.add("params", new Gson().toJsonTree(params));
    entry.add("observation", observation);
    Files.writeString(path, entry + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }
}
