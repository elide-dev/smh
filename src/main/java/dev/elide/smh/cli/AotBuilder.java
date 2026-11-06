// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import dev.elide.smh.runtime.AotPolicy;
import dev.elide.smh.runtime.Platform;
import jakarta.inject.Singleton;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.stream.*;

/** Compiles benchmark sources and reuses a base layer only for the same build variant. */
@Singleton
public class AotBuilder {
  @FunctionalInterface
  interface Commands {
    void run(List<String> command, Path cwd) throws Exception;
  }

  private final Commands commands;
  private final boolean defaultCommands;

  public AotBuilder() {
    commands = AotBuilder::runCommand;
    defaultCommands = true;
  }

  AotBuilder(Commands commands) {
    this.commands = Objects.requireNonNull(commands);
    defaultCommands = false;
  }

  public Path build(BuildConfig c, boolean dryRun) throws Exception {
    return build(c, dryRun, false);
  }

  public Path build(BuildConfig c, boolean dryRun, boolean baseOnly) throws Exception {
    c.validate(!dryRun || c.prepare.isEmpty());
    if (!c.execution.equals("aot"))
      throw new IllegalArgumentException("Use smh bench for runtime-loaded execution");
    if (baseOnly && !c.layers) throw new IllegalArgumentException("--base-only requires layers");
    Platform platform = Platform.current();
    Files.createDirectories(c.output);
    if (!dryRun) Files.deleteIfExists(c.output.resolve("build.json"));
    List<Path> runtime = BytecodeBuilder.runtime(c);
    Path classes =
        baseOnly
            ? null
            : BytecodeBuilder.build(
                c, dryRun, (command, cwd) -> execute(command, cwd, false, c.verbose));
    List<String> flags = flags(c);
    Process versionProbe =
        new ProcessBuilder(append(c.nativeImage, "--version")).redirectErrorStream(true).start();
    String version =
        new String(
            versionProbe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    if (versionProbe.waitFor() != 0)
      throw new IOException("Compiler version probe failed: " + version.strip());
    List<String> identity =
        new ArrayList<>(
            List.of(
                c.nativeImage.subList(1, c.nativeImage.size()).toString(),
                version,
                System.getProperty("os.name"),
                System.getProperty("os.arch")));
    identity.add(BuildCache.compilerIdentity(c.nativeImage));
    identity.addAll(flags);
    for (Path path : runtime)
      identity.add(path.getFileName() + ":" + digest(Files.readAllBytes(path)));
    if (c.profile != null) identity.add("profile:" + digest(Files.readAllBytes(c.profile)));
    String key =
        digest(String.join("\n", identity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Path layerDir = c.home.resolve("layers").resolve(key);
    Path layer = layerDir.resolve("smh-base.nil");
    List<String> app = new ArrayList<>(c.nativeImage);
    app.addAll(flags);
    if (c.layers) {
      {
        List<String> base = new ArrayList<>(c.nativeImage);
        base.addAll(flags);
        base.addAll(
            List.of(
                "-cp",
                classpath(runtime),
                "-H:LayerCreate=smh-base.nil,package=dev.elide.smh.runtime",
                "-o",
                platform.layerImageName("smh-base-" + key.substring(0, 16))));
        try {
          if (dryRun) {
            if (!LayerCache.valid(layerDir)) execute(base, layerDir, true, c.verbose);
          } else
            LayerCache.prepare(
                layerDir,
                platform.sharedLibrary("smh-base-" + key.substring(0, 16)),
                staging -> execute(base, staging, false, c.verbose));
        } catch (IOException failure) {
          if (!c.pgo.equals("off"))
            throw new IOException(
                "Native Image rejected the PGO layer build. On toolchains without layered PGO"
                    + " support, use --no-layers for the full-image PGO workflow.",
                failure);
          throw failure;
        }
      }
      app.add("-H:LayerUse=" + layer);
      if (!platform.loaderOrigin().isEmpty())
        app.add("-H:NativeLinkerOption=-Wl,-rpath," + platform.loaderOrigin());
    }
    if (baseOnly) return layer;
    List<Path> applicationCp = new ArrayList<>(runtime);
    applicationCp.addAll(c.classpath);
    applicationCp.add(classes);
    Path binary =
        c.output.resolve(
            c.shared ? platform.sharedLibrary("benchmarks") : platform.executable("benchmarks"));
    if (c.shared) app.add("--shared");
    app.addAll(
        List.of(
            "-cp",
            classpath(applicationCp),
            "dev.elide.smh.generated.Main",
            "-o",
            binary.toString()));
    if (!dryRun) Files.deleteIfExists(binary);
    execute(app, c.output, dryRun, c.verbose);
    if (!dryRun && !Files.isRegularFile(binary))
      throw new IOException("Compiler produced no binary: " + binary);
    if (c.layers && !dryRun) {
      String library = platform.sharedLibrary("smh-base-" + key.substring(0, 16));
      try (var archive = new java.util.zip.ZipFile(layer.toFile())) {
        var entry = archive.getEntry(library);
        if (entry == null) throw new IOException("Layer archive is missing " + library);
        try (var input = archive.getInputStream(entry)) {
          Files.copy(input, c.output.resolve(library), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    JsonObject manifest = new JsonObject();
    manifest.addProperty("layerKey", key);
    manifest.addProperty("optimization", c.optimization);
    manifest.addProperty("pgo", c.pgo);
    manifest.addProperty("layered", c.layers);
    manifest.addProperty("binary", binary.toString());
    manifest.addProperty("toolchain", version.strip());
    JsonArray settings = new JsonArray();
    flags.forEach(settings::add);
    manifest.add("nativeArgs", settings);
    if (!dryRun)
      Files.writeString(
          c.output.resolve("build.json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
    return binary;
  }

  /** The native-image options every SMH benchmark image needs, in command order. */
  static List<String> flags(BuildConfig c) {
    List<String> flags = new ArrayList<>(List.of("--no-fallback", "-O" + c.optimization));
    // JMH's padding check assumes HotSpot layout. Its compiler-only path does not use
    // tombstone fields: initialize the flag/check on the builder JVM, never against SVM layout.
    flags.addAll(AotPolicy.requiredCompilerFlags());
    if (c.pgo.equals("instrument")) flags.add("--pgo-instrument");
    if (c.pgo.equals("use")) flags.add("--pgo=" + c.profile);
    flags.addAll(c.nativeArgs);
    return flags;
  }

  /** The compiler contract for builds that Gradle, Maven, or Bazel own instead of SMH. */
  static JsonObject contract(BuildConfig c) throws IOException {
    JsonObject result = new JsonObject();
    JsonObject javac = new JsonObject();
    javac.addProperty("processor", "dev.elide.smh.processor.BenchmarkProcessor");
    JsonArray processorPath = new JsonArray();
    BytecodeBuilder.processorPath(c).forEach(path -> processorPath.add(path.toString()));
    javac.add("processorPath", processorPath);
    JsonArray classpath = new JsonArray();
    BytecodeBuilder.runtime(c).forEach(path -> classpath.add(path.toString()));
    javac.add("classpath", classpath);
    JsonArray options = new JsonArray();
    for (String option :
        List.of("--release", "25", "-Asmh.target=" + Platform.current().targetTriple()))
      options.add(option);
    if (c.observer != null) options.add("-Asmh.observer=" + c.observer);
    javac.add("options", options);
    result.add("javac", javac);
    JsonObject image = new JsonObject();
    JsonArray flags = new JsonArray();
    flags(c).forEach(flags::add);
    image.add("flags", flags);
    image.addProperty("mainClass", "dev.elide.smh.generated.Main");
    image.addProperty("layers", c.layers);
    image.addProperty("layersNote", "SMH-managed base layers are unavailable to external builds");
    result.add("nativeImage", image);
    result.addProperty(
        "benchHint", "smh bench --binary <image> runs the result without rebuilding");
    return result;
  }

  private static String classpath(List<Path> paths) {
    return paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
  }

  private static List<String> append(List<String> command, String argument) {
    List<String> result = new ArrayList<>(command);
    result.add(argument);
    return result;
  }

  private static String digest(byte[] value) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private void execute(List<String> command, Path cwd, boolean dryRun, boolean verbose)
      throws Exception {
    if (dryRun || verbose) TerminalDisplay.buildMessages().println(String.join(" ", command));
    if (!dryRun) {
      if (defaultCommands) runCommand(command, cwd, verbose);
      else commands.run(command, cwd);
    }
  }

  static void runCommand(List<String> command, Path cwd) throws Exception {
    runCommand(command, cwd, false);
  }

  static void runCommand(List<String> command, Path cwd, boolean verbose) throws Exception {
    int code =
        TerminalDisplay.build(
            new ProcessBuilder(command).directory(cwd.toFile()),
            "Build · " + Path.of(command.getFirst()).getFileName(),
            cwd,
            verbose);
    if (code != 0)
      throw new IOException(
          "Build command failed (exit "
              + code
              + "): "
              + command.getFirst()
              + "; see retained compiler logs");
  }
}
