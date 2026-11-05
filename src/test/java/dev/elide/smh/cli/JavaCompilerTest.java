// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.elide.smh.runtime.Platform;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class JavaCompilerTest {
  @TempDir Path directory;

  private Path executable(Path path) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "compiler selection fixture; never executed");
    assertTrue(path.toFile().setExecutable(true));
    return path;
  }

  @Test
  void prefersElideButExplicitJdkOverridesItOnEveryPlatform() throws Exception {
    for (String os : List.of("Linux", "Mac OS X", "Windows")) {
      Platform platform = Platform.parse(os, "amd64");
      Path home = directory.resolve(os + " JDK");
      Path javac = executable(home.resolve("bin").resolve(platform.executable("javac")));
      Path bin = directory.resolve(os + " tools");
      Path elide = executable(bin.resolve(platform.executable("elide")));
      Map<String, String> env =
          Map.of(
              "PATH",
              directory.resolve("absent") + File.pathSeparator + File.pathSeparator + bin,
              "JAVA_HOME",
              "invalid");
      assertEquals(
          List.of(elide.toString(), "javac", "--"),
          JavaCompiler.command(null, platform, env, "invalid"));
      assertEquals(List.of(javac.toString()), JavaCompiler.command(home, platform, env, "invalid"));
      assertThrows(
          IllegalArgumentException.class,
          () -> JavaCompiler.command(directory.resolve("missing JDK"), platform, env, ""));
    }
  }

  @Test
  void fallsBackToEnvironmentThenRuntimeJdkAndDiagnosesMissingCompilers() throws Exception {
    Platform platform = Platform.current();
    Path home = directory.resolve("environment JDK");
    Path javac = executable(home.resolve("bin").resolve(platform.executable("javac")));
    Path runtime = directory.resolve("runtime JDK");
    Path runtimeJavac = executable(runtime.resolve("bin").resolve(platform.executable("javac")));
    assertEquals(
        List.of(javac.toString()),
        JavaCompiler.command(
            null, platform, Map.of("JAVA_HOME", home.toString()), runtime.toString()));
    assertEquals(
        List.of(runtimeJavac.toString()),
        JavaCompiler.command(null, platform, Map.of("JAVA_HOME", " "), runtime.toString()));
    assertEquals(
        List.of(runtimeJavac.toString()),
        JavaCompiler.command(null, platform, Map.of(), runtime.toString()));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaCompiler.command(null, platform, Map.of(), ""))
            .getMessage()
            .contains("--java-home"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JavaCompiler.command(
                null, platform, Map.of("JAVA_HOME", "missing"), runtime.toString()));
    Path nonExecutable =
        directory.resolve("nonexecutable/bin").resolve(platform.executable("javac"));
    Files.createDirectories(nonExecutable);
    assertThrows(
        IllegalArgumentException.class,
        () -> JavaCompiler.command(nonExecutable.getParent().getParent(), platform, Map.of(), ""));
  }

  @Test
  void buildJsonResolvesJavaHomeRelativeToItsOwnDirectory() throws Exception {
    Path config =
        Files.writeString(
            directory.resolve("build.json"), "{\"sources\":[\"src\"],\"javaHome\":\"jdk\"}");
    assertEquals(directory.resolve("jdk"), BuildConfig.read(config, null).javaHome);
  }
}
