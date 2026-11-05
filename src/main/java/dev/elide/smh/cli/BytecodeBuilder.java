// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Source compilation cache shared by AOT and runtime-loaded execution. */
final class BytecodeBuilder {
  record Artifact(Path classes, String key) {}

  private BytecodeBuilder() {}

  static Path build(BuildConfig c, boolean dryRun, AotBuilder.Commands commands) throws Exception {
    return prepare(c, dryRun, commands).classes();
  }

  static Artifact prepare(BuildConfig c, boolean dryRun, AotBuilder.Commands commands)
      throws Exception {
    if (dryRun)
      return new Artifact(
          compile(c, true, commands).getParent().getParent().getParent().getParent().getParent(),
          "dry-run");
    Path output = c.output;
    try {
      c.output = c.home.resolve("bytecode");
      BuildCache.Result result =
          BuildCache.artifact(
              c,
              false,
              settings -> compile(settings, false, commands),
              BuildCache::bytecodeFingerprint);
      System.err.println(
          (result.hit() ? "Bytecode cache hit · " : "Bytecode built · ")
              + result.key().substring(0, 12));
      return new Artifact(
          result.binary().getParent().getParent().getParent().getParent().getParent(),
          result.key());
    } finally {
      c.output = output;
    }
  }

  private static Path compile(BuildConfig c, boolean dryRun, AotBuilder.Commands commands)
      throws Exception {
    return compile(c, dryRun, commands, false);
  }

  static Path compile(BuildConfig c, boolean dryRun, AotBuilder.Commands commands, boolean jmh)
      throws Exception {
    Platform platform = Platform.current();
    List<Path> libraries;
    try (var stream = Files.list(c.home.resolve("lib"))) {
      libraries = stream.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
    }
    List<Path> runtime = new ArrayList<>(List.of(c.home.resolve("smh-runtime.jar")));
    libraries.stream()
        .filter(
            p -> p.getFileName().toString().matches("(gson|jmh-core|nativeimage|word)-.*\\.jar"))
        .forEach(runtime::add);
    List<Path> javaSources = new ArrayList<>();
    for (Path source : c.sources) {
      if (Files.isDirectory(source))
        try (var paths = Files.walk(source)) {
          paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(javaSources::add);
        }
      else if (Files.isRegularFile(source) && source.toString().endsWith(".java"))
        javaSources.add(source);
      else throw new IllegalArgumentException("Missing Java source: " + source);
    }
    if (javaSources.isEmpty()) throw new IllegalArgumentException("No Java sources found");
    org.slf4j.LoggerFactory.getLogger(AotBuilder.class)
        .debug("Planning {} benchmark source files in {}", javaSources.size(), c.output);
    Path classes = c.output.resolve("classes"), generated = c.output.resolve("generated");
    Files.createDirectories(classes);
    Files.createDirectories(generated);
    List<Path> processor = new ArrayList<>(List.of(c.home.resolve("smh.jar")));
    processor.addAll(libraries);
    List<Path> compileCp = new ArrayList<>(runtime);
    compileCp.addAll(c.classpath);
    List<String> compile = new ArrayList<>(JavaCompiler.command(c.javaHome));
    compile.addAll(
        List.of(
            "--release",
            "25",
            "-cp",
            classpath(compileCp),
            "-processorpath",
            classpath(processor),
            "-processor",
            jmh
                ? "org.openjdk.jmh.generators.BenchmarkProcessor"
                : "dev.elide.smh.processor.BenchmarkProcessor",
            "-s",
            generated.toString(),
            "-d",
            classes.toString()));
    if (!jmh && c.observer != null) compile.add("-Asmh.observer=" + c.observer);
    if (!jmh) compile.add("-Asmh.target=" + platform.targetTriple());
    javaSources.forEach(p -> compile.add(p.toString()));
    if (dryRun || c.verbose) System.err.println(String.join(" ", compile));
    if (!dryRun) commands.run(compile, c.output);
    Path marker =
        classes.resolve(jmh ? "META-INF/BenchmarkList" : "dev/elide/smh/generated/Main.class");
    if (!dryRun && !Files.exists(marker))
      throw new IllegalArgumentException("No @Benchmark methods found");
    return marker;
  }

  static List<Path> runtime(BuildConfig c) throws IOException {
    List<Path> result = new ArrayList<>(List.of(c.home.resolve("smh-runtime.jar")));
    try (var paths = Files.list(c.home.resolve("lib"))) {
      paths
          .filter(
              p -> p.getFileName().toString().matches("(gson|jmh-core|nativeimage|word)-.*\\.jar"))
          .sorted()
          .forEach(result::add);
    }
    return result;
  }

  static String classpath(List<Path> paths) {
    return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
  }
}
