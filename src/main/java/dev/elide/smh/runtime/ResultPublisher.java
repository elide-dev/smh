// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;

/** Completed trials only: publish after teardown, never inside measured work. */
final class ResultPublisher {
  private final String directory;
  private final JsonObject execution;
  private int count;

  ResultPublisher(String directory) {
    this(directory, System.getenv("SMH_EXECUTION_IDENTITY"));
  }

  ResultPublisher(String directory, String identity) {
    this.directory = directory;
    execution =
        identity == null
            ? null
            : com.google.gson.JsonParser.parseString(identity).getAsJsonObject();
  }

  JsonObject publish(JsonObject result) throws IOException {
    if (execution != null) {
      result.getAsJsonObject("smh").add("execution", execution.deepCopy());
      result.add("jvmArgs", execution.getAsJsonArray("runtimeArgs").deepCopy());
    }
    if (directory != null) {
      Path root = Path.of(directory);
      Path pending = root.resolve("pending.json");
      Files.writeString(pending, result.toString());
      Files.move(pending, root.resolve(count++ + ".json"), StandardCopyOption.ATOMIC_MOVE);
    }
    return result;
  }
}
