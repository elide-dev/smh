// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.proof;

import com.google.gson.JsonParser;
import dev.elide.smh.cli.CallgrindRunner;
import java.nio.file.*;

/** Runs the production importer against deliberately broken real simulator runs. */
public class SimulationImport {
  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    System.out.println(
        CallgrindRunner.importResults(
            JsonParser.parseString(Files.readString(directory.resolve("execution.json")))
                .getAsJsonArray(),
            directory));
  }
}
