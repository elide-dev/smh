// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Local content cache. Published artifact directories are never rebuilt in place. */
final class BuildCache {
  @FunctionalInterface
  interface Compiler {
    Path build(BuildConfig config) throws Exception;
  }

  @FunctionalInterface
  interface Identity {
    String fingerprint(BuildConfig config) throws Exception;
  }

  record Result(Path binary, boolean hit, String key) {}

  private BuildCache() {}

  static Result build(BuildConfig config, boolean rebuild, Compiler compiler) throws Exception {
    return build(config, rebuild, compiler, BuildCache::fingerprint);
  }

  static Result build(BuildConfig config, boolean rebuild, Compiler compiler, Identity identity)
      throws Exception {
    config.validate();
    if (config.shared) throw new IllegalArgumentException("smh bench requires an executable build");
    Path output = config.output;
    Path root = output.resolve(".smh-cache").toAbsolutePath();
    Files.createDirectories(root);
    // Serialize publication for processes using the same output directory.
    try (var lockFile = new RandomAccessFile(root.resolve("build.lock").toFile(), "rw")) {
      // Closing the file also closes its channel and releases the lock on every exit path.
      lockFile.getChannel().lock();
      String key = identity.fingerprint(config);
      Path receipt = root.resolve(key + ".json");
      if (!rebuild && Files.isRegularFile(receipt)) {
        Path cached = verified(receipt, root);
        if (cached != null) return new Result(cached, true, key);
      }
      Path generation = Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
      try {
        config.output = generation;
        Path binary = compiler.build(config);
        config.output = output;
        if (!key.equals(identity.fingerprint(config)))
          throw new IOException(
              "Build inputs changed during compilation; cache entry not published");
        JsonObject record = new JsonObject();
        if (!binary.toAbsolutePath().startsWith(generation) || !Files.isRegularFile(binary))
          throw new IOException("Compiler did not produce the cache artifact");
        record.addProperty("key", key);
        record.addProperty("binary", root.relativize(binary.toAbsolutePath()).toString());
        record.add("files", artifacts(generation, root));
        Path pending = Files.createTempFile(root, "receipt-", ".tmp");
        try {
          Files.writeString(pending, record.toString());
          Files.move(
              pending,
              receipt,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } finally {
          Files.deleteIfExists(pending);
        }
        return new Result(binary, false, key);
      } finally {
        config.output = output;
      }
    }
  }

  static Path verified(Path receipt, Path root) throws Exception {
    try {
      JsonObject record = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
      if (!receipt.getFileName().toString().equals(record.get("key").getAsString() + ".json"))
        return null;
      Path binary = root.resolve(record.get("binary").getAsString()).normalize();
      if (!binary.startsWith(root) || !Files.isRegularFile(binary)) return null;
      JsonObject files = record.getAsJsonObject("files");
      if (files.isEmpty()) return null;
      for (var entry : files.entrySet()) {
        Path file = root.resolve(entry.getKey()).normalize();
        if (!file.startsWith(root)
            || !Files.isRegularFile(file)
            || !hash(file).equals(entry.getValue().getAsString())) return null;
      }
      if (!files.has(root.relativize(binary).toString())) return null;
      return binary;
    } catch (IOException | RuntimeException invalid) {
      return null;
    }
  }

  private static JsonObject artifacts(Path directory, Path root) throws Exception {
    JsonObject result = new JsonObject();
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList())
        result.addProperty(root.relativize(path).toString(), hash(path));
    }
    return result;
  }

  static String fingerprint(BuildConfig c) throws Exception {
    return fingerprint(c, System.getProperty("os.name").startsWith("Windows"));
  }

  static String fingerprint(BuildConfig c, boolean windows) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    add(digest, "smh-build-cache-v1");
    add(digest, c.output.toAbsolutePath().normalize().toString());
    add(digest, c.optimization + "/" + c.pgo + "/" + c.layers + "/" + c.shared + "/" + c.observer);
    add(digest, c.nativeArgs.toString());
    add(digest, System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
    add(digest, environmentIdentity(System.getenv()));
    for (Path source : c.sources) {
      if (Files.isDirectory(source)
          && c.output.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize()))
        throw new IOException("Build output must be outside source directories for caching");
      tree(digest, source);
    }
    for (Path dependency : c.classpath) tree(digest, dependency);
    tree(digest, c.home.resolve("smh.jar"));
    tree(digest, c.home.resolve("smh-runtime.jar"));
    tree(digest, c.home.resolve("lib"));
    for (Path extra : c.cacheInputs) tree(digest, extra);
    if (c.profile != null) tree(digest, c.profile);
    tool(digest, c.nativeImage);
    tool(digest, JavaCompiler.command(c.javaHome));
    // Track the host C compiler as well; additional native dependencies belong in cacheInputs.
    String cc = System.getenv().getOrDefault("CC", "gcc");
    if (!windows) tool(digest, List.of(cc));
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Build options matter; terminal and per-invocation scratch locations do not. */
  static String environmentIdentity(Map<String, String> environment) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    // Hash values without persisting them. Elide assigns a fresh TMPDIR to each script run.
    for (var entry : new TreeMap<>(environment).entrySet()) {
      if (Set.of(
              "_",
              "PWD",
              "OLDPWD",
              "SHLVL",
              "TERM",
              "COLORTERM",
              "NO_COLOR",
              "CI",
              "TMPDIR",
              "TMP",
              "TEMP")
          .contains(entry.getKey())) continue;
      add(digest, entry.getKey());
      add(digest, entry.getValue());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static String compilerIdentity(List<String> command) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    tool(digest, command);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void tool(MessageDigest digest, List<String> command) throws Exception {
    add(digest, command.toString());
    Path executable = executable(command.getFirst());
    tree(digest, executable);
    // A launcher hash alone does not identify its compiler implementation. Hash the JDK tree
    // when present, and Elide's adjacent lib directory for its embedded compiler/runtime.
    Path installation = executable.toAbsolutePath().getParent().getParent();
    if (Files.isRegularFile(installation.resolve("release"))) {
      tree(digest, installation.resolve("release"));
      tree(digest, installation.resolve("lib"));
    } else if (Files.isDirectory(installation.resolve("conf"))) {
      tree(digest, installation.resolve("conf"));
      tree(digest, installation.resolve("lib"));
    }
    List<String> probe = new ArrayList<>(command);
    probe.add("--version");
    Process process = new ProcessBuilder(probe).redirectErrorStream(true).start();
    String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0)
      throw new IOException("Toolchain identity probe failed: " + command.getFirst());
    add(digest, version);
  }

  static Path executable(String name) throws IOException {
    // The single-variable lookup honors Windows' case-insensitive environment names.
    return executable(name, Objects.requireNonNullElse(System.getenv("PATH"), ""));
  }

  static Path executable(String name, String searchPath) throws IOException {
    Path direct = Path.of(name);
    if (direct.isAbsolute() || direct.getParent() != null) return direct.toAbsolutePath();
    for (String directory : searchPath.split(File.pathSeparator)) {
      Path candidate = Path.of(directory).resolve(name);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
        return candidate.toAbsolutePath();
    }
    throw new IOException("Build tool is not on PATH: " + name);
  }

  private static void tree(MessageDigest digest, Path path) throws Exception {
    add(digest, path.toAbsolutePath().normalize().toString());
    if (Files.isDirectory(path)) {
      try (var paths = Files.walk(path)) {
        for (Path file : paths.sorted().toList()) {
          if (Files.isSymbolicLink(file) && Files.isDirectory(file))
            throw new IOException(
                "Directory symlinks require an explicit resolved cache input: " + file);
          if (Files.isRegularFile(file)) {
            add(digest, path.relativize(file).toString());
            add(digest, hash(file));
          }
        }
      }
    } else add(digest, hash(path));
  }

  static String hash(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void add(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
