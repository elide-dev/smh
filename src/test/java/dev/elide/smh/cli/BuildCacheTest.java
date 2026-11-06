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
    BuildCache.Identity identity =
        settings -> BuildCache.Fingerprint.of(BuildCache.hash(settings.sources.getFirst()));
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
    BuildCache.Identity identity = settings -> BuildCache.Fingerprint.of("test");
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
                settings -> BuildCache.Fingerprint.of("key" + calls.incrementAndGet())));
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
    Path first = Files.createDirectory(directory.resolve("first"));
    Path second = Files.createDirectory(directory.resolve("second"));
    Path executable = Files.writeString(second.resolve("tool"), "tool");
    assertTrue(executable.toFile().setExecutable(true));
    String searchPath = first + File.pathSeparator + second;
    assertEquals(executable, BuildCache.executable("tool", searchPath));
    if (Files.getFileStore(first).supportsFileAttributeView("posix")) {
      Path blocked = Files.writeString(first.resolve("tool"), "not executable");
      Files.setPosixFilePermissions(
          blocked, Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ));
      assertEquals(executable, BuildCache.executable("tool", searchPath));
      assertThrows(IOException.class, () -> BuildCache.executable("tool", first.toString()));
    }
  }

  @Test
  void fingerprintTracksBytesOptionsDependenciesAndCompilerImplementations() throws Exception {
    var c = config();
    Path toolHome = Files.createDirectories(directory.resolve("tools"));
    Path bin = Files.createDirectories(toolHome.resolve("bin"));
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    Path launcher;
    if (windows) {
      launcher =
          Files.copy(
              Path.of(System.getenv("SystemRoot"), "System32/cmd.exe"),
              bin.resolve("compiler.exe"));
      c.javaHome = Path.of(System.getProperty("java.home"));
      c.nativeImage = List.of(launcher.toString(), "/d", "/c", "echo compiler-v1");
    } else {
      launcher = Files.writeString(bin.resolve("javac"), "#!/bin/sh\necho compiler-v1\n");
      assertTrue(launcher.toFile().setExecutable(true));
      c.javaHome = toolHome;
      c.nativeImage = List.of(launcher.toString());
    }
    String original = BuildCache.fingerprint(c).key();
    assertEquals(original, BuildCache.fingerprint(c).key());
    var components = BuildCache.fingerprint(c).components();
    for (String name :
        List.of("sources", "classpath", "sdk", "options", "native-image", "javac", "platform"))
      assertTrue(components.containsKey(name), name);
    assertTrue(components.keySet().stream().noneMatch(name -> name.equals("env:PATH")));
    if (!windows) {
      assertTrue(components.containsKey("cc"));
      assertNotEquals(
          original,
          BuildCache.fingerprint(c, true).key(),
          "Windows omits the Unix C compiler probe");
    }
    c.verbose = true;
    assertEquals(original, BuildCache.fingerprint(c).key());
    c.optimization = "3";
    assertNotEquals(original, BuildCache.fingerprint(c).key());
    c.optimization = "2";
    c.nativeArgs.add("-march=compatibility");
    assertNotEquals(original, BuildCache.fingerprint(c).key());
    c.nativeArgs.clear();
    Path extra = Files.writeString(directory.resolve("native-input"), "one");
    c.cacheInputs.add(extra);
    String before = BuildCache.fingerprint(c).key();
    Files.writeString(extra, "two");
    assertNotEquals(before, BuildCache.fingerprint(c).key());
    c.classpath.add(extra);
    assertNotEquals(before, BuildCache.fingerprint(c).key());
    c.profile = extra;
    assertNotEquals(before, BuildCache.fingerprint(c).key());
    assertTrue(BuildCache.fingerprint(c).components().containsKey("profile"));
    c.profile = null;
    assertFalse(BuildCache.bytecodeFingerprint(c).components().containsKey("native-image"));
    Path lib = Files.createDirectories(toolHome.resolve("lib"));
    Files.writeString(toolHome.resolve("release"), "jdk-v1");
    Path implementation = Files.writeString(lib.resolve("compiler"), "one");
    before = BuildCache.fingerprint(c).key();
    Files.writeString(toolHome.resolve("release"), "jdk-v2");
    assertNotEquals(before, BuildCache.fingerprint(c).key(), "release files are hashed");
    before = BuildCache.fingerprint(c).key();
    // Toolchain libraries are identified by path, size, and timestamp, not by their bytes.
    var timestamp = Files.getLastModifiedTime(implementation);
    Files.writeString(implementation, "two");
    Files.setLastModifiedTime(implementation, timestamp);
    assertEquals(before, BuildCache.fingerprint(c).key());
    Files.writeString(implementation, "two-longer");
    assertNotEquals(before, BuildCache.fingerprint(c).key());
    Files.delete(toolHome.resolve("release"));
    Files.createDirectory(toolHome.resolve("conf"));
    before = BuildCache.fingerprint(c).key();
    Files.writeString(toolHome.resolve("conf").resolve("settings"), "three");
    assertNotEquals(before, BuildCache.fingerprint(c).key());
    assertNotEquals(BuildCache.compilerIdentity(c.nativeImage), original);
    Path sources = Files.createDirectory(directory.resolve("src"));
    Files.writeString(sources.resolve("Other.java"), "other");
    c.sources.add(sources);
    Files.createSymbolicLink(sources.resolve("linked-file"), extra);
    before = BuildCache.fingerprint(c).key();
    Files.writeString(sources.resolve("Other.java"), "updated");
    assertNotEquals(before, BuildCache.fingerprint(c).key());
    c.output = sources.resolve("output");
    assertThrows(IOException.class, () -> BuildCache.fingerprint(c));
    c.output = directory.resolve("out");
    Files.createSymbolicLink(sources.resolve("linked"), lib);
    assertThrows(IOException.class, () -> BuildCache.fingerprint(c));
    Files.delete(sources.resolve("linked"));
    if (windows) c.nativeImage = List.of(launcher.toString(), "/d", "/c", "exit 9");
    else Files.writeString(launcher, "#!/bin/sh\nexit 9\n");
    assertThrows(IOException.class, () -> BuildCache.fingerprint(c));
    assertTrue(Files.isRegularFile(BuildCache.executable(windows ? "cmd.exe" : "gcc")));
    // Installations with a release file or conf directory but no lib tree are still identified.
    for (String marker : List.of("release", "conf")) {
      Path install = Files.createDirectories(directory.resolve("install-" + marker));
      Files.createDirectories(install.resolve("bin"));
      Path tool;
      if (windows) tool = Files.copy(launcher, install.resolve("bin/tool.exe"));
      else {
        tool = Files.writeString(install.resolve("bin/tool"), "#!/bin/sh\necho v\n");
        assertTrue(tool.toFile().setExecutable(true));
      }
      if (marker.equals("release")) Files.writeString(install.resolve("release"), "v");
      else Files.createDirectory(install.resolve("conf"));
      List<String> command =
          windows ? List.of(tool.toString(), "/d", "/c", "echo v") : List.of(tool.toString());
      String bare = BuildCache.compilerIdentity(command);
      Files.createDirectories(install.resolve("lib"));
      Files.writeString(install.resolve("lib/impl"), "impl");
      assertNotEquals(bare, BuildCache.compilerIdentity(command));
    }
  }

  @Test
  void declaredCacheEnvironmentNamesAreParsedAndValidated() throws Exception {
    Path home = Files.createDirectories(directory.resolve("home"));
    String base =
        "{\"home\":\""
            + home.toString().replace("\\", "\\\\")
            + "\",\"sources\":[\"src\"],\"cacheEnvironment\":";
    Path file = directory.resolve("build.json");
    Files.writeString(file, base + "[\"MY_FLAGS\", \"OTHER\"]}");
    assertEquals(List.of("MY_FLAGS", "OTHER"), BuildConfig.read(file, null).cacheEnvironment);
    for (String invalid : List.of("\"MY_FLAGS\"", "[1]", "[{}]", "[\"\"]", "[\" \"]")) {
      Files.writeString(file, base + invalid + "}");
      assertThrows(IllegalArgumentException.class, () -> BuildConfig.read(file, null), invalid);
    }
  }

  @Test
  void onlyBuildAffectingOrDeclaredEnvironmentVariablesInvalidateTheCache() throws Exception {
    var first = new LinkedHashMap<String, String>();
    first.put("JAVA_TOOL_OPTIONS", "-Xmx4g");
    first.put("TMPDIR", "/tmp/elide-invocation-one");
    first.put("WSL_INTEROP", "/run/WSL/11293_interop");
    first.put("PATH", "/tools/bin");
    var second = new LinkedHashMap<String, String>();
    second.put("PATH", "/other/bin");
    second.put("WSL_INTEROP", "/run/WSL/99_interop");
    second.put("TMPDIR", "/tmp/elide-invocation-two");
    second.put("JAVA_TOOL_OPTIONS", "-Xmx4g");
    second.put("NATIVE_COMPILER_CUSTOM_INPUT", "changed");
    var none = List.<String>of();
    assertEquals(
        BuildCache.environmentIdentity(first, none), BuildCache.environmentIdentity(second, none));
    second.put("JAVA_TOOL_OPTIONS", "-Xmx8g");
    assertNotEquals(
        BuildCache.environmentIdentity(first, none), BuildCache.environmentIdentity(second, none));
    second.put("JAVA_TOOL_OPTIONS", "-Xmx4g");
    var declared = List.of("NATIVE_COMPILER_CUSTOM_INPUT");
    assertNotEquals(
        BuildCache.environmentIdentity(first, declared),
        BuildCache.environmentIdentity(second, declared));
    first.put("NATIVE_COMPILER_CUSTOM_INPUT", "changed");
    assertEquals(
        BuildCache.environmentIdentity(first, declared),
        BuildCache.environmentIdentity(second, declared));
    assertEquals(
        Set.of("env:JAVA_TOOL_OPTIONS", "env:NATIVE_COMPILER_CUSTOM_INPUT"),
        BuildCache.environment(first, declared, false).keySet());
    assertEquals(
        Set.of("env:JAVA_TOOL_OPTIONS"), BuildCache.environment(first, none, false).keySet());
    assertEquals(
        Set.of("env:java_home"),
        BuildCache.environment(Map.of("java_home", "/jdk"), none, true).keySet());
    assertEquals(
        Set.of(), BuildCache.environment(Map.of("java_home", "/jdk"), none, false).keySet());
  }

  @Test
  void missesExplainWhichInputsChangedAndOldGenerationsArePruned() throws Exception {
    BuildConfig c = config();
    Path root = c.output.resolve(".smh-cache");
    AtomicInteger builds = new AtomicInteger();
    BuildCache.Compiler compiler =
        settings ->
            Files.writeString(
                settings.output.resolve("benchmarks"), "binary " + builds.incrementAndGet());
    var components = new TreeMap<String, String>();
    components.put("sources", "s1");
    components.put("env:CC", "gcc");
    BuildCache.Identity identity =
        settings ->
            new BuildCache.Fingerprint(BuildCache.digest(components.toString()), components);
    var first = BuildCache.build(c, false, compiler, identity);
    assertEquals("no previous build", first.reason());
    assertEquals("build inputs unchanged", BuildCache.build(c, false, compiler, identity).reason());
    assertEquals("rebuild requested", BuildCache.build(c, true, compiler, identity).reason());
    Files.writeString(root.resolve(first.key() + ".json"), "not json");
    assertEquals(
        "cached artifact failed verification",
        BuildCache.build(c, false, compiler, identity).reason());
    Thread.sleep(20);
    components.put("sources", "s2");
    components.remove("env:CC");
    components.put("env:CFLAGS", "-O3");
    var second = BuildCache.build(c, false, compiler, identity);
    assertEquals("changed: env:CC, env:CFLAGS, sources", second.reason());
    assertNotEquals(first.key(), second.key());
    for (int i = 0; i < 8; i++) components.put("extra" + i, "x");
    assertTrue(BuildCache.build(c, false, compiler, identity).reason().endsWith(" +2"));
    JsonObject record =
        JsonParser.parseString(Files.readString(root.resolve(second.key() + ".json")))
            .getAsJsonObject();
    assertEquals("s2", record.getAsJsonObject("components").get("sources").getAsString());
    var relabeled = new BuildCache.Fingerprint("other-key", components);
    assertEquals("cache key format changed", BuildCache.explain(root, relabeled));
    Files.writeString(root.resolve("f".repeat(64) + ".json"), "{}");
    Files.writeString(root.resolve("e".repeat(64) + ".json"), "broken");
    assertEquals("cache key format changed", BuildCache.explain(root, relabeled));
    Files.delete(root.resolve("f".repeat(64) + ".json"));
    Files.delete(root.resolve("e".repeat(64) + ".json"));
    // Explaining a key that already has a receipt skips that receipt and compares the next one.
    var latest = identity.fingerprint(c);
    assertTrue(BuildCache.explain(root, latest).startsWith("changed: "));
    for (int i = 0; i < BuildCache.RETAINED_GENERATIONS + 3; i++) {
      Thread.sleep(5);
      components.put("sources", "generation-" + i);
      assertFalse(BuildCache.build(c, false, compiler, identity).hit());
    }
    assertEquals(BuildCache.RETAINED_GENERATIONS, BuildCache.receipts(root).size());
    List<Path> directories;
    try (var files = Files.list(root)) {
      directories = files.filter(Files::isDirectory).toList();
    }
    assertEquals(BuildCache.RETAINED_GENERATIONS, directories.size());
    assertTrue(Files.isRegularFile(root.resolve("build.lock")));
    assertTrue(BuildCache.build(c, false, compiler, identity).hit());
    Files.createDirectory(root.resolve("orphan"));
    Files.writeString(root.resolve("orphan").resolve("stale"), "stale");
    BuildCache.prune(root, "unknown-key");
    assertFalse(Files.exists(root.resolve("orphan")));
    assertEquals(BuildCache.RETAINED_GENERATIONS, BuildCache.receipts(root).size());
  }
}
