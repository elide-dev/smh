// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlled artifact/failure injection. Marker files are never executed as native binaries. */
@Tag("portable")
class BuilderExecutionTest {
  @TempDir Path directory;

  private BuildConfig config() throws Exception {
    BuildConfig c = new BuildConfig();
    c.home = Files.createDirectories(directory.resolve("distribution"));
    Files.writeString(c.home.resolve("smh.jar"), "processor marker");
    Files.writeString(c.home.resolve("smh-runtime.jar"), "runtime marker");
    Files.createDirectories(c.home.resolve("lib"));
    c.output = directory.resolve("output");
    c.sources.add(Files.writeString(directory.resolve("Work.java"), "public class Work {}"));
    c.nativeImage = List.of(java());
    return c;
  }

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", Platform.current().executable("java"))
        .toString();
  }

  private void compileMarker(BuildConfig c) throws IOException {
    Path main = c.output.resolve("classes/dev/elide/smh/generated/Main.class");
    Files.createDirectories(main.getParent());
    Files.writeString(main, "compiled-class marker");
  }

  private static String option(List<String> command, String key) {
    return command.get(command.indexOf(key) + 1);
  }

  private static void archive(List<String> command, Path cwd, boolean libraryPresent)
      throws IOException {
    String image = option(command, "-o");
    String library =
        image
            + switch (Platform.current().os()) {
              case LINUX -> ".so";
              case MACOS -> ".dylib";
              case WINDOWS -> ".dll";
            };
    try (ZipOutputStream output =
        new ZipOutputStream(Files.newOutputStream(cwd.resolve("smh-base.nil")))) {
      output.putNextEntry(new ZipEntry(libraryPresent ? library : "wrong-library"));
      output.write("native-library marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  @Test
  void artifactLifecycleReusesImmutableBytecodeExtractsLayersAndRecordsAConsistentManifest()
      throws Exception {
    BuildConfig c = config();
    Path stale = c.output.resolve("classes/old/stale.class");
    Files.createDirectories(stale.getParent());
    Files.writeString(stale, "stale");
    Path oldSource = c.output.resolve("generated/old.java");
    Files.createDirectories(oldSource.getParent());
    Files.writeString(oldSource, "stale");
    List<List<String>> executed = new ArrayList<>();
    AotBuilder builder =
        new AotBuilder(
            (command, cwd) -> {
              executed.add(List.copyOf(command));
              if (command.contains("--release")) compileMarker(c);
              else if (command.stream().anyMatch(a -> a.startsWith("-H:LayerCreate=")))
                archive(command, cwd, true);
              else Files.writeString(Path.of(option(command, "-o")), "native-executable marker");
            });
    Path binary = builder.build(c, false);
    assertEquals(3, executed.size());
    assertTrue(Files.exists(stale), "Old output is not used as cached bytecode");
    assertTrue(Files.exists(oldSource));
    assertEquals("native-executable marker", Files.readString(binary));
    var manifest =
        JsonParser.parseString(Files.readString(c.output.resolve("build.json"))).getAsJsonObject();
    assertEquals(binary.toString(), manifest.get("binary").getAsString());
    assertTrue(manifest.get("layered").getAsBoolean());
    String library =
        Platform.current()
            .sharedLibrary("smh-base-" + manifest.get("layerKey").getAsString().substring(0, 16));
    assertEquals("native-library marker", Files.readString(c.output.resolve(library)));
    executed.clear();
    builder.build(c, false);
    assertEquals(1, executed.size(), "Both bytecode and base are reused; only native linking runs");
    c.layers = false;
    builder.build(c, false);
    assertFalse(
        JsonParser.parseString(Files.readString(c.output.resolve("build.json")))
            .getAsJsonObject()
            .get("layered")
            .getAsBoolean());
    Files.writeString(c.sources.getFirst(), "changed");
    assertThrows(
        IllegalArgumentException.class, () -> new AotBuilder((command, cwd) -> {}).build(c, false));
    assertFalse(
        Files.exists(c.output.resolve("build.json")),
        "A failed rebuild cannot retain a success manifest");
  }

  @Test
  void compilerAndArchiveFailuresCannotProduceSuccessfulManifests() throws Exception {
    BuildConfig c = config();
    assertThrows(
        IllegalArgumentException.class, () -> new AotBuilder((command, cwd) -> {}).build(c, false));
    IOException failed = new IOException("compiler rejected layer");
    AotBuilder builder =
        new AotBuilder(
            (command, cwd) -> {
              if (command.contains("--release")) compileMarker(c);
              else throw failed;
            });
    assertSame(failed, assertThrows(IOException.class, () -> builder.build(c, false)));
    c.pgo = "instrument";
    IOException diagnostic = assertThrows(IOException.class, () -> builder.build(c, false));
    assertSame(failed, diagnostic.getCause());
    assertTrue(diagnostic.getMessage().contains("--no-layers"));
    c.pgo = "off";
    AotBuilder missingLibrary =
        new AotBuilder(
            (command, cwd) -> {
              if (command.contains("--release")) compileMarker(c);
              else if (command.stream().anyMatch(a -> a.startsWith("-H:LayerCreate=")))
                archive(command, cwd, false);
              else Files.writeString(Path.of(option(command, "-o")), "native-executable marker");
            });
    IOException missing = assertThrows(IOException.class, () -> missingLibrary.build(c, false));
    assertTrue(missing.getMessage().contains("Layer archive is missing"));
    assertFalse(Files.exists(c.output.resolve("build.json")));
    c.layers = false;
    assertTrue(
        assertThrows(
                IOException.class,
                () ->
                    new AotBuilder(
                            (command, cwd) -> {
                              if (command.contains("--release")) compileMarker(c);
                            })
                        .build(c, false))
            .getMessage()
            .contains("Compiler produced no binary"));
    c.nativeImage = List.of(java(), "--not-a-real-java-option");
    assertTrue(
        assertThrows(IOException.class, () -> new AotBuilder().build(c, true))
            .getMessage()
            .contains("Compiler version probe failed"));
  }

  @Test
  void realProcessStatusIsPropagated() throws Exception {
    AotBuilder.runCommand(List.of(java(), "--version"), directory);
    IOException failure =
        assertThrows(
            IOException.class,
            () -> AotBuilder.runCommand(List.of(java(), "--not-a-real-java-option"), directory));
    assertTrue(failure.getMessage().contains("exit 1"));
    assertThrows(
        IOException.class,
        () -> AotBuilder.runCommand(List.of(directory.resolve("absent").toString()), directory));
  }
}
