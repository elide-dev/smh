// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class LayerCacheTest {
  @TempDir Path directory;

  private void archive(Path root, String entry) throws IOException {
    try (var zip = new ZipOutputStream(Files.newOutputStream(root.resolve("smh-base.nil")))) {
      zip.putNextEntry(new ZipEntry(entry));
      zip.write(new byte[] {1, 2, 3});
      zip.closeEntry();
    }
  }

  @Test
  void completeLayersAreVerifiedAndCorruptionOrMissingReceiptsRebuild() throws Exception {
    Path layer = directory.resolve("key");
    AtomicInteger builds = new AtomicInteger();
    LayerCache.Producer producer =
        root -> {
          builds.incrementAndGet();
          archive(root, "base.so");
        };
    LayerCache.prepare(layer, "base.so", producer);
    LayerCache.prepare(layer, "base.so", producer);
    assertEquals(1, builds.get());
    Files.writeString(layer.resolve("smh-base.nil"), "corrupt");
    LayerCache.prepare(layer, "base.so", producer);
    assertEquals(2, builds.get());
    Files.delete(layer.resolve("SHA256"));
    LayerCache.prepare(layer, "base.so", producer);
    assertEquals(3, builds.get());
    Files.delete(layer.resolve("smh-base.nil"));
    LayerCache.prepare(layer, "base.so", producer);
    assertEquals(4, builds.get());
  }

  @Test
  void failedOrIncompleteBuildsPublishNothingAndReleaseTheirLock() throws Exception {
    Path layer = directory.resolve("key");
    assertThrows(
        IOException.class,
        () ->
            LayerCache.prepare(
                layer,
                "base.so",
                root -> {
                  Files.createDirectories(root.resolve("partial"));
                  Files.writeString(root.resolve("partial/log"), "failed");
                  throw new IOException("compiler failed");
                }));
    assertFalse(Files.exists(layer));
    assertThrows(
        IOException.class,
        () -> LayerCache.prepare(layer, "base.so", root -> archive(root, "wrong")));
    assertFalse(Files.exists(layer));
    LayerCache.prepare(layer, "base.so", root -> archive(root, "base.so"));
    try (var paths = Files.list(directory)) {
      assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith("pending-")));
    }
  }
}
