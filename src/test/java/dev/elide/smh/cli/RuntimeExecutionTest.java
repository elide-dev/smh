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
class RuntimeExecutionTest {
  @TempDir Path directory;

  private BuildConfig config() throws Exception {
    BuildConfig c = new BuildConfig();
    c.home = Files.createDirectories(directory.resolve("sdk"));
    Files.createDirectories(c.home.resolve("lib"));
    Files.writeString(c.home.resolve("smh.jar"), "processor");
    Files.writeString(c.home.resolve("smh-runtime.jar"), "host-api");
    c.output = directory.resolve("out");
    c.sources.add(Files.writeString(directory.resolve("Work.java"), "source"));
    c.javaHome = Path.of(System.getProperty("java.home"));
    return c;
  }

  private Path marker(BuildConfig c, AtomicInteger calls) throws Exception {
    return BytecodeBuilder.build(
        c,
        false,
        (command, cwd) -> {
          calls.incrementAndGet();
          Path main = cwd.resolve("classes/dev/elide/smh/generated/Main.class");
          Files.createDirectories(main.getParent());
          Files.writeString(main, "bytecode-marker-not-executed");
        });
  }

  @Test
  void bytecodeIsSharedAcrossExecutionAndOptimizationButInvalidatesOnContent() throws Exception {
    BuildConfig c = config();
    AtomicInteger calls = new AtomicInteger();
    Path first = marker(c, calls);
    c.execution = "crema";
    c.optimization = "3";
    c.layers = false;
    assertEquals(first, marker(c, calls));
    c.execution = "hotspot-c2";
    assertEquals(first, marker(c, calls));
    assertEquals(1, calls.get());
    Files.writeString(c.sources.getFirst(), "modified");
    assertNotEquals(first, marker(c, calls));
    assertEquals(2, calls.get());
    assertEquals(directory.resolve("out"), c.output);
  }

  @Test
  void launchPlansKeepPayloadOffHostClasspathAndRecordCompilerChoice() throws Exception {
    BuildConfig c = config();
    Path classes = marker(c, new AtomicInteger());
    Path run = directory.resolve("run.json");
    c.execution = "hotspot-c2";
    c.runtimeArgs.add("-Xmx128m");
    ProcessBuilder jvm = RuntimeExecution.prepare(c, run);
    assertTrue(jvm.command().contains("-XX:-UseJVMCICompiler"));
    assertTrue(jvm.command().contains("-XX:-TieredCompilation"));
    assertFalse(jvm.command().get(jvm.command().indexOf("-cp") + 1).contains(classes.toString()));
    assertTrue(jvm.command().contains(classes.toString()));
    assertTrue(
        jvm.environment().get("SMH_EXECUTION_IDENTITY").contains("\"compilerRequested\":\"c2\""));
    c.execution = "hotspot";
    assertFalse(RuntimeExecution.prepare(c, run).command().contains("-XX:-TieredCompilation"));
    c.execution = "hotspot-c2";
    c.runtimeJavaHome = c.javaHome;
    c.javaHome = null;
    // Keep the javac selection stable for this test's cached input identity.
    c.javaHome = c.runtimeJavaHome;
    assertNotNull(RuntimeExecution.prepare(c, run));
    c.javaHome = null;
    c.runtimeJavaHome = null;
    assertThrows(IllegalArgumentException.class, () -> RuntimeExecution.prepare(c, run));
    c.javaHome = Path.of(System.getProperty("java.home"));
    c.execution = "crema";
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    c.cremaHost = Files.writeString(directory.resolve("host"), "native-marker-not-executed");
    JsonObject receipt = new JsonObject();
    receipt.addProperty("sha256", "wrong");
    Path manifest = Path.of(c.cremaHost + ".json");
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    receipt.addProperty("sha256", BuildCache.hash(c.cremaHost));
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    JsonObject nativeFiles = new JsonObject();
    receipt.add("files", nativeFiles);
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    nativeFiles.addProperty(c.cremaHost.getFileName().toString(), "wrong");
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    nativeFiles.addProperty(c.cremaHost.getFileName().toString(), BuildCache.hash(c.cremaHost));
    nativeFiles.addProperty("../outside", "wrong");
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    nativeFiles.remove("../outside");
    Path companion = Files.writeString(directory.resolve("libfixture.so"), "native library marker");
    nativeFiles.addProperty(companion.getFileName().toString(), BuildCache.hash(companion));
    receipt.addProperty("runtimeSha256", "wrong");
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    receipt.addProperty("runtimeSha256", BuildCache.hash(c.home.resolve("smh-runtime.jar")));
    JsonObject dependencies = new JsonObject();
    receipt.add("dependencies", dependencies);
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    dependencies.addProperty("smh-runtime.jar", "wrong");
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    dependencies.addProperty("smh-runtime.jar", BuildCache.hash(c.home.resolve("smh-runtime.jar")));
    JsonArray flags = new JsonArray();
    receipt.add("flags", flags);
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    flags.add("-H:+RuntimeClassLoading");
    Files.writeString(manifest, receipt.toString());
    assertThrows(IOException.class, () -> RuntimeExecution.prepare(c, run));
    flags.add("-H:+GraalJITCompileAtRuntime");
    Files.writeString(manifest, receipt.toString());
    c.runtimeArgs.add("-XX:+JITTraceCompilation");
    ProcessBuilder crema = RuntimeExecution.prepare(c, run);
    assertTrue(crema.environment().get("SMH_EXECUTION_IDENTITY").contains("diagnostic"));
    assertEquals(c.cremaHost.toString(), crema.command().getFirst());
    assertFalse(crema.command().contains("-cp"));
    assertTrue(crema.environment().get("SMH_EXECUTION_IDENTITY").contains("ristretto"));
    Files.createDirectory(directory.resolve("alias"));
    c.cremaHost = directory.resolve("alias/../host");
    assertTrue(RuntimeExecution.prepare(c, run).command().getFirst().endsWith("host"));
    c.execution = "aot";
    assertThrows(IllegalArgumentException.class, () -> RuntimeExecution.prepare(c, run));
  }

  @Test
  void invalidExecutionOptionsFailBeforeCompilation() throws Exception {
    BuildConfig c = config();
    c.execution = "unknown";
    assertThrows(IllegalArgumentException.class, c::validate);
    c.execution = "crema";
    c.shared = true;
    assertThrows(IllegalArgumentException.class, c::validate);
    c.shared = false;
    c.pgo = "instrument";
    assertThrows(IllegalArgumentException.class, c::validate);
    c.pgo = "off";
    c.observer = "Custom";
    assertThrows(IllegalArgumentException.class, c::validate);
    c.observer = null;
    for (String argument :
        List.of(
            "-jar",
            "@options",
            "-XX:+UseJVMCICompiler",
            "-XX:+TieredCompilation",
            "-XX:-JITCompilation",
            "-XX:-UseCompiler",
            "-XX:+EnableJVMCI",
            "-XX:AOTCache=x",
            "-XX:SharedArchiveFile=x")) {
      c.runtimeArgs.clear();
      c.runtimeArgs.add(argument);
      assertThrows(IllegalArgumentException.class, c::validate);
    }
    JsonObject json = new JsonObject();
    json.addProperty("home", c.home.toString());
    JsonArray sources = new JsonArray();
    sources.add(c.sources.getFirst().toString());
    json.add("sources", sources);
    json.addProperty("execution", "hotspot-c2");
    json.addProperty("runtimeJavaHome", "jdk");
    json.addProperty("cremaHost", "smh-crema");
    JsonArray arguments = new JsonArray();
    arguments.add("-Xmx64m");
    json.add("runtimeArgs", arguments);
    BuildConfig read =
        BuildConfig.read(
            Files.writeString(directory.resolve("config.json"), json.toString()), null);
    assertEquals("hotspot-c2", read.execution);
    assertEquals(directory.resolve("jdk"), read.runtimeJavaHome);
    assertEquals(directory.resolve("smh-crema"), read.cremaHost);
    assertEquals(List.of("-Xmx64m"), read.runtimeArgs);
    c.runtimeArgs.clear();
    c.runtimeArgs.addAll(List.of("-Dwork=1", "-Xms32m", "-Xmx64m", "-XX:+PrintCompilation"));
    c.validate();
  }

  @Test
  void nativeReportsRetainBuildManifestAndExecutableIdentity() throws Exception {
    Path binary = Files.writeString(directory.resolve("benchmark"), "native marker");
    var artifact = new BuildCache.Result(binary, true, "key");
    Path config = directory.resolve("run.json");
    JsonObject identity =
        JsonParser.parseString(
                RuntimeExecution.aot(artifact, config).environment().get("SMH_EXECUTION_IDENTITY"))
            .getAsJsonObject();
    assertEquals("aot", identity.get("backend").getAsString());
    assertEquals("external", identity.get("provenance").getAsString());
    assertEquals(BuildCache.hash(binary), identity.get("hostSha256").getAsString());
    assertThrows(
        IllegalArgumentException.class,
        () -> RuntimeExecution.external(directory.resolve("missing")));
    if (Files.getFileStore(binary).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(
          binary, Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ));
      assertThrows(IllegalArgumentException.class, () -> RuntimeExecution.external(binary));
    }
    assertTrue(binary.toFile().setExecutable(true));
    var external = RuntimeExecution.external(binary);
    assertTrue(external.hit());
    assertTrue(external.key().startsWith("external-"));
    assertEquals("external binary", external.reason());
    Files.writeString(directory.resolve("build.json"), "{\"optimization\":\"3\"}");
    identity =
        JsonParser.parseString(
                RuntimeExecution.aot(artifact, config).environment().get("SMH_EXECUTION_IDENTITY"))
            .getAsJsonObject();
    assertEquals("3", identity.getAsJsonObject("hostBuild").get("optimization").getAsString());
    assertEquals("smh-build", identity.get("provenance").getAsString());
    Files.writeString(directory.resolve("build.json"), "{\"pgo\":\"instrument\"}");
    identity =
        JsonParser.parseString(
                RuntimeExecution.aot(artifact, config).environment().get("SMH_EXECUTION_IDENTITY"))
            .getAsJsonObject();
    assertEquals("training", identity.get("purpose").getAsString());
  }
}
