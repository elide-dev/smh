// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;

/** Completed trials only: publish after teardown, never inside measured work. */
final class ResultPublisher {
  private final String directory;
  private int count;

  ResultPublisher(String directory) {
    this.directory = directory;
  }

  JsonObject publish(JsonObject result) throws IOException {
    if (directory != null) {
      Path root = Path.of(directory);
      Path pending = root.resolve("pending.json");
      Files.writeString(pending, result.toString());
      Files.move(pending, root.resolve(count++ + ".json"), StandardCopyOption.ATOMIC_MOVE);
    }
    return result;
  }
}
