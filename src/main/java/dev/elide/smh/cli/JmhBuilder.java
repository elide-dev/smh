// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Separate bytecode cache: official JMH-generated harnesses are never SMH adapters. */
final class JmhBuilder {
  static final String VERSION = "1.37";

  private JmhBuilder() {}

  static List<Path> libraries(BuildConfig c) throws IOException {
    List<Path> jars = new ArrayList<>();
    for (String name :
        List.of(
            "jmh-core-1.37.jar",
            "jmh-generator-annprocess-1.37.jar",
            "jopt-simple-5.0.4.jar",
            "commons-math3-3.6.1.jar")) {
      Path jar = c.home.resolve("lib").resolve(name);
      if (!Files.isRegularFile(jar)) throw new IOException("Missing pinned JMH dependency: " + jar);
      jars.add(jar);
    }
    return jars;
  }

  static BuildCache.Result prepare(BuildConfig c, AotBuilder.Commands commands) throws Exception {
    libraries(c);
    Path output = c.output;
    try {
      c.output = c.home.resolve("jmh-bytecode");
      BuildCache.Result result =
          BuildCache.artifact(
              c,
              false,
              settings -> {
                BytecodeBuilder.compile(settings, false, commands, true);
                return jar(
                    settings.output.resolve("classes"), settings.output.resolve("benchmarks.jar"));
              },
              BuildCache::bytecodeFingerprint);
      System.err.println(
          (result.hit() ? "JMH bytecode cache hit · " : "JMH bytecode built · ")
              + result.key().substring(0, 12));
      return result;
    } finally {
      c.output = output;
    }
  }

  static Path jar(Path classes, Path target) throws IOException {
    try (var jar = new JarOutputStream(Files.newOutputStream(target));
        var paths = Files.walk(classes)) {
      for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
        JarEntry entry =
            new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/'));
        entry.setTime(0);
        jar.putNextEntry(entry);
        Files.copy(file, jar);
        jar.closeEntry();
      }
    }
    return target;
  }
}
