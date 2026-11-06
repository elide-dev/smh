// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import dev.elide.smh.runtime.Platform;
import java.io.File;
import java.nio.file.*;
import java.util.*;

/** Select a source compiler without requiring Elide in an installed SDK. */
final class JavaCompiler {
  private JavaCompiler() {}

  static List<String> command(Path override) {
    return command(
        override, Platform.current(), System.getenv(), System.getProperty("java.home", ""));
  }

  static List<String> command(
      Path override, Platform platform, Map<String, String> environment, String runtimeHome) {
    if (override != null) return jdk(override, platform);
    String path = environment.getOrDefault("PATH", "");
    for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
      if (entry.isBlank()) continue;
      Path elide = Path.of(entry).resolve(platform.executable("elide")).toAbsolutePath();
      if (BuildCache.executableFile(elide)) return List.of(elide.toString(), "javac", "--");
    }
    String home = environment.getOrDefault("JAVA_HOME", "");
    if (home.isBlank()) home = runtimeHome;
    if (home.isBlank())
      throw new IllegalArgumentException(
          "No Java compiler: install Elide, set JAVA_HOME, or pass --java-home with a JDK 25"
              + " installation");
    return jdk(Path.of(home), platform);
  }

  private static List<String> jdk(Path home, Platform platform) {
    Path javac = home.toAbsolutePath().resolve("bin").resolve(platform.executable("javac"));
    if (!BuildCache.executableFile(javac))
      throw new IllegalArgumentException("Java home has no executable JDK compiler: " + javac);
    return List.of(javac.toString());
  }
}
