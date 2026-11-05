// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("portable")
class CliTest {
  @TempDir Path directory;

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", Platform.current().executable("java"))
        .toString();
  }

  private record Output(int status, String stdout, String stderr) {}

  private Output invoke(String... args) {
    PrintStream out = System.out, err = System.err;
    ByteArrayOutputStream stdout = new ByteArrayOutputStream(),
        stderr = new ByteArrayOutputStream();
    try (PrintStream captureOut = new PrintStream(stdout);
        PrintStream captureErr = new PrintStream(stderr)) {
      System.setOut(captureOut);
      System.setErr(captureErr);
      int status = Main.execute(args);
      return new Output(status, stdout.toString(), stderr.toString());
    } finally {
      System.setOut(out);
      System.setErr(err);
    }
  }

  @Test
  void realMicronautCreatesRootAndSubcommandsAndPreservesExitCodes() {
    Output root = invoke();
    assertEquals(0, root.status);
    assertTrue(root.stdout.startsWith("Usage: smh"), root.stdout);
    assertTrue(root.stdout.contains("build"));
    assertEquals(0, invoke("--help").status);
    Output version = invoke("--version");
    assertEquals(0, version.status);
    assertEquals("smh 0.1.1", version.stdout.strip()); // x-release-please-version
    assertEquals(0, invoke("build", "--help").status);
    assertEquals(0, invoke("simulate", "--help").status);
    assertEquals(2, invoke("simulate").status);
    Output missing = invoke("build");
    assertEquals(2, missing.status);
    assertTrue(missing.stderr.contains("--config"), missing.stderr);
    assertEquals(2, invoke("unrecognized-command").status);
  }

  @Test
  void buildOptionsOverrideJsonThroughThePublicParser() throws Exception {
    Path home = Files.createDirectories(directory.resolve("distribution"));
    Files.writeString(home.resolve("smh.jar"), "processor marker");
    Files.writeString(home.resolve("smh-runtime.jar"), "runtime marker");
    Files.createDirectories(home.resolve("lib"));
    Path source = Files.writeString(directory.resolve("Work.java"), "public class Work {}");
    JsonObject config = new JsonObject();
    config.addProperty("home", home.toString());
    config.addProperty("output", directory.resolve("original").toString());
    com.google.gson.JsonArray sources = new com.google.gson.JsonArray();
    sources.add(source.toString());
    config.add("sources", sources);
    config.addProperty("nativeImage", java());
    Path file = Files.writeString(directory.resolve("build.json"), config.toString());
    Output defaults = invoke("build", "--config", file.toString(), "--dry-run");
    assertEquals(0, defaults.status, defaults.stderr);
    assertEquals(
        directory
            .resolve("original")
            .resolve(Platform.current().executable("benchmarks"))
            .toString(),
        defaults.stdout.strip());
    Path profile = Files.writeString(directory.resolve("training.iprof"), "profile marker");
    Path output = directory.resolve("override");
    Output overridden =
        invoke(
            "build",
            "--config",
            file.toString(),
            "--home",
            home.toString(),
            "--java-home",
            System.getProperty("java.home"),
            "-O3",
            "--pgo",
            "use",
            "--profile",
            profile.toString(),
            "--output",
            output.toString(),
            "--no-layers",
            "--shared",
            "--dry-run");
    assertEquals(0, overridden.status, overridden.stderr);
    assertEquals(
        output.resolve(Platform.current().sharedLibrary("benchmarks")).toString(),
        overridden.stdout.strip());
    assertTrue(overridden.stderr.contains("-O3"));
    assertTrue(overridden.stderr.contains(Platform.current().executable("javac")));
    assertTrue(overridden.stderr.contains("--pgo=" + profile));
    assertFalse(overridden.stderr.contains("-H:LayerUse"));
  }

  @Test
  void runUsesFreshProcessesForwardsConfigurationAndReturnsTheChildStatus() throws Exception {
    Path source =
        Files.writeString(
            directory.resolve("Exit.java"),
            """
            class Exit {
              public static void main(String[] args) throws Exception {
                if (args.length != 0) java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "forwarded");
                System.exit(7);
              }
            }
            """);
    Main.Run run = new Main.Run();
    run.binary = Path.of(java());
    run.config = source;
    assertEquals(7, run.call());
    run.bootstrap = Path.of(java());
    run.binary = source;
    run.config = directory.resolve("child-output");
    assertEquals(7, run.call());
    assertEquals("forwarded", Files.readString(run.config));
    run.profileOutput = directory.resolve("profile.iprof");
    assertThrows(IllegalArgumentException.class, run::call);
    run.bootstrap = null;
    run.binary = Path.of(java());
    run.config = null;
    assertNotEquals(
        0, run.call(), "HotSpot rejects the SVM profile option; failure must propagate");
    assertEquals(0, new CommandLine(new Main.Run()).execute("--help"));
  }
}
