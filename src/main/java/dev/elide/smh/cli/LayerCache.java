// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Cross-output producer lock and atomic publication of a verified, relocatable layer bundle. */
final class LayerCache {
  @FunctionalInterface
  interface Producer {
    void build(Path directory) throws Exception;
  }

  private LayerCache() {}

  static boolean valid(Path directory) throws Exception {
    Path archive = directory.resolve("smh-base.nil");
    Path checksum = directory.resolve("SHA256");
    return Files.isRegularFile(archive)
        && Files.isRegularFile(checksum)
        && Files.readString(checksum).equals(BuildCache.hash(archive));
  }

  static void prepare(Path directory, String library, Producer producer) throws Exception {
    Path root = directory.getParent();
    Files.createDirectories(root);
    try (var lock = new RandomAccessFile(Path.of(directory + ".lock").toFile(), "rw")) {
      lock.getChannel().lock();
      if (valid(directory)) {
        System.err.println("Layer cache hit · " + directory.getFileName());
        return;
      }
      Path staging = Files.createTempDirectory(root, "pending-");
      try {
        producer.build(staging);
        Path layer = staging.resolve("smh-base.nil");
        try (ZipFile zip = new ZipFile(layer.toFile())) {
          if (zip.getEntry(library) == null)
            throw new IOException("Layer archive is missing " + library);
        }
        Files.writeString(staging.resolve("SHA256"), BuildCache.hash(layer));
        // Preserve rejected generations for diagnosis, but never select them again.
        if (Files.exists(directory))
          Files.move(
              directory,
              root.resolve("rejected-" + UUID.randomUUID()),
              StandardCopyOption.ATOMIC_MOVE);
        Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
        System.err.println("Layer ready · " + TerminalDisplay.path(directory));
      } catch (Exception failure) {
        Path diagnostics = root.resolve("failed-" + UUID.randomUUID());
        try {
          Files.move(staging, diagnostics, StandardCopyOption.ATOMIC_MOVE);
          System.err.println("Layer build diagnostics · " + TerminalDisplay.path(diagnostics));
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }
  }
}
