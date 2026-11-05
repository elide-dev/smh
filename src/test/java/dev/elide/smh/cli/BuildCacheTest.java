// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class BuildCacheTest {
  @TempDir Path directory;

  BuildConfig config() throws Exception {
    BuildConfig c = new BuildConfig();
    c.home = Files.createDirectories(directory.resolve("sdk"));
    Files.writeString(c.home.resolve("smh.jar"), "sdk");
    Files.writeString(c.home.resolve("smh-runtime.jar"), "runtime");
    Files.createDirectories(c.home.resolve("lib"));
    c.output = directory.resolve("out");
    c.sources.add(Files.writeString(directory.resolve("Work.java"), "source"));
    c.layers = false;
    return c;
  }

  @Test
  void immutableCacheRebuildsOnContentChangesAndCorruption() throws Exception {
    BuildConfig c = config();
    Path output = c.output;
    AtomicInteger builds = new AtomicInteger();
    BuildCache.Compiler compiler =
        settings -> {
          builds.incrementAndGet();
          Files.writeString(settings.output.resolve("build.json"), "manifest");
          return Files.writeString(settings.output.resolve("benchmarks"), "binary");
        };
    BuildCache.Identity identity = settings -> BuildCache.hash(settings.sources.getFirst());
    var first = BuildCache.build(c, false, compiler, identity);
    assertFalse(first.hit());
    assertTrue(BuildCache.build(c, false, compiler, identity).hit());
    assertEquals(1, builds.get());
    Path source = c.sources.getFirst();
    var timestamp = Files.getLastModifiedTime(source);
    Files.writeString(source, "changed");
    Files.setLastModifiedTime(source, timestamp);
    var second = BuildCache.build(c, false, compiler, identity);
    assertFalse(second.hit());
    assertNotEquals(first.key(), second.key());
    assertEquals("binary", Files.readString(first.binary()));
    var forced = BuildCache.build(c, true, compiler, identity);
    assertFalse(forced.hit());
    assertNotEquals(second.binary(), forced.binary());
    Files.writeString(forced.binary(), "corrupt");
    var repaired = BuildCache.build(c, false, compiler, identity);
    assertFalse(repaired.hit());
    Files.delete(repaired.binary());
    assertFalse(BuildCache.build(c, false, compiler, identity).hit());
    assertEquals(output, c.output);
  }

  @Test
  void failuresNeverPublishAnEntryAndRestoreConfiguration() throws Exception {
    var c = config();
    var output = c.output;
    BuildCache.Identity identity = settings -> "test";
    assertThrows(
        IOException.class,
        () ->
            BuildCache.build(
                c,
                false,
                settings -> {
                  throw new IOException("compiler failed");
                },
                identity));
    assertEquals(output, c.output);
    assertFalse(Files.exists(output.resolve(".smh-cache/test.json")));
    for (Path bad : List.of(directory.resolve("outside"), output.resolve("missing"))) {
      assertThrows(IOException.class, () -> BuildCache.build(c, false, settings -> bad, identity));
    }
    assertThrows(
        IOException.class,
        () -> BuildCache.build(c, false, settings -> settings.output.resolve("missing"), identity));
    AtomicInteger calls = new AtomicInteger();
    assertThrows(
        IOException.class,
        () ->
            BuildCache.build(
                c,
                false,
                settings -> Files.writeString(settings.output.resolve("binary"), "b"),
                settings -> "key" + calls.incrementAndGet()));
    assertEquals(output, c.output);
    c.shared = true;
    assertThrows(
        IllegalArgumentException.class,
        () -> BuildCache.build(c, false, settings -> null, identity));
  }

  @Test
  void malformedOrIncompleteReceiptsAreMisses() throws Exception {
    Path root = Files.createDirectory(directory.resolve("cache"));
    Path receipt = root.resolve("key.json");
    Path binary = Files.writeString(root.resolve("binary"), "b");
    Path other = Files.writeString(root.resolve("other"), "c");
    assertNull(BuildCache.verified(receipt, root));
    for (String bad : List.of("not json", "{}", "[]")) {
      Files.writeString(receipt, bad);
      assertNull(BuildCache.verified(receipt, root));
    }
    JsonObject record = new JsonObject();
    record.addProperty("key", "wrong");
    Files.writeString(receipt, record.toString());
    assertNull(BuildCache.verified(receipt, root));
    record.addProperty("key", "key");
    for (String name : List.of("../outside", "missing", "binary")) {
      record.addProperty("binary", name);
      record.add("files", new JsonObject());
      Files.writeString(receipt, record.toString());
      assertNull(BuildCache.verified(receipt, root));
    }
    for (String name : List.of("../outside", "missing", "other", "binary")) {
      JsonObject files = new JsonObject();
      files.addProperty(name, name.equals("other") ? BuildCache.hash(other) : "wrong");
      record.add("files", files);
      Files.writeString(receipt, record.toString());
      assertNull(BuildCache.verified(receipt, root));
    }
    record.getAsJsonObject("files").addProperty("binary", BuildCache.hash(binary));
    Files.writeString(receipt, record.toString());
    assertEquals(binary, BuildCache.verified(receipt, root));
  }

  @Test
  void toolLookupRequiresAnExecutable() throws Exception {
    assertEquals(
        directory.resolve("tool"), BuildCache.executable(directory.resolve("tool").toString()));
    assertEquals(Path.of("./tool").toAbsolutePath(), BuildCache.executable("./tool"));
    assertThrows(IOException.class, () -> BuildCache.executable("smh-nonexistent-tool-94912"));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs({
    org.junit.jupiter.api.condition.OS.LINUX,
    org.junit.jupiter.api.condition.OS.MAC
  })
  void fingerprintTracksBytesOptionsDependenciesAndCompilerImplementations() throws Exception {
    var c = config();
    Path toolHome = Files.createDirectories(directory.resolve("tools"));
    Path bin = Files.createDirectories(toolHome.resolve("bin"));
    Path launcher = Files.writeString(bin.resolve("javac"), "#!/bin/sh\necho compiler-v1\n");
    assertTrue(launcher.toFile().setExecutable(true));
    c.javaHome = toolHome;
    c.nativeImage = List.of(launcher.toString());
    String original = BuildCache.fingerprint(c);
    assertEquals(original, BuildCache.fingerprint(c));
    c.verbose = true;
    assertEquals(original, BuildCache.fingerprint(c));
    c.optimization = "3";
    assertNotEquals(original, BuildCache.fingerprint(c));
    c.optimization = "2";
    c.nativeArgs.add("-march=compatibility");
    assertNotEquals(original, BuildCache.fingerprint(c));
    c.nativeArgs.clear();
    Path extra = Files.writeString(directory.resolve("native-input"), "one");
    c.cacheInputs.add(extra);
    String before = BuildCache.fingerprint(c);
    Files.writeString(extra, "two");
    assertNotEquals(before, BuildCache.fingerprint(c));
    c.classpath.add(extra);
    assertNotEquals(before, BuildCache.fingerprint(c));
    c.profile = extra;
    assertNotEquals(before, BuildCache.fingerprint(c));
    c.profile = null;
    Path lib = Files.createDirectories(toolHome.resolve("lib"));
    Files.writeString(toolHome.resolve("release"), "jdk-v1");
    Path implementation = Files.writeString(lib.resolve("compiler"), "one");
    before = BuildCache.fingerprint(c);
    var timestamp = Files.getLastModifiedTime(implementation);
    Files.writeString(implementation, "two");
    Files.setLastModifiedTime(implementation, timestamp);
    assertNotEquals(before, BuildCache.fingerprint(c));
    Files.delete(toolHome.resolve("release"));
    Files.createDirectory(toolHome.resolve("conf"));
    before = BuildCache.fingerprint(c);
    Files.writeString(implementation, "three");
    assertNotEquals(before, BuildCache.fingerprint(c));
    assertNotEquals(BuildCache.compilerIdentity(List.of(launcher.toString())), original);
    Path sources = Files.createDirectory(directory.resolve("src"));
    Files.writeString(sources.resolve("Other.java"), "other");
    c.sources.add(sources);
    Files.createSymbolicLink(sources.resolve("linked-file"), extra);
    before = BuildCache.fingerprint(c);
    Files.writeString(sources.resolve("Other.java"), "updated");
    assertNotEquals(before, BuildCache.fingerprint(c));
    c.output = sources.resolve("output");
    assertThrows(IOException.class, () -> BuildCache.fingerprint(c));
    c.output = directory.resolve("out");
    Files.createSymbolicLink(sources.resolve("linked"), lib);
    assertThrows(IOException.class, () -> BuildCache.fingerprint(c));
    Files.delete(sources.resolve("linked"));
    Files.writeString(launcher, "#!/bin/sh\nexit 9\n");
    assertThrows(IOException.class, () -> BuildCache.fingerprint(c));
    assertTrue(Files.isRegularFile(BuildCache.executable("gcc")));
  }

  @Test
  void launcherScratchDirectoriesDoNotInvalidateCompilerInputs() throws Exception {
    var first = new LinkedHashMap<String, String>();
    first.put("JAVA_TOOL_OPTIONS", "-Xmx4g");
    first.put("TMPDIR", "/tmp/elide-invocation-one");
    first.put("PATH", "/tools/bin");
    var second = new LinkedHashMap<String, String>();
    second.put("PATH", "/tools/bin");
    second.put("TMPDIR", "/tmp/elide-invocation-two");
    second.put("JAVA_TOOL_OPTIONS", "-Xmx4g");
    assertEquals(BuildCache.environmentIdentity(first), BuildCache.environmentIdentity(second));
    second.put("JAVA_TOOL_OPTIONS", "-Xmx8g");
    assertNotEquals(BuildCache.environmentIdentity(first), BuildCache.environmentIdentity(second));
    second.put("JAVA_TOOL_OPTIONS", "-Xmx4g");
    second.put("NATIVE_COMPILER_CUSTOM_INPUT", "changed");
    assertNotEquals(BuildCache.environmentIdentity(first), BuildCache.environmentIdentity(second));
  }
}
