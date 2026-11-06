// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
    Fingerprint fingerprint(BuildConfig config) throws Exception;
  }

  /** A cache key plus the named input digests it was computed from, for explaining misses. */
  record Fingerprint(String key, SortedMap<String, String> components) {
    static Fingerprint of(String key) {
      return new Fingerprint(key, new TreeMap<>(Map.of("key", key)));
    }
  }

  record Result(Path binary, boolean hit, String key, String reason) {
    Result(Path binary, boolean hit, String key) {
      this(binary, hit, key, "build inputs not fingerprinted");
    }
  }

  /** Receipts and artifact generations retained per cache root, newest first. */
  static final int RETAINED_GENERATIONS = 8;

  /**
   * Environment variables that can change compiler or linker output. Everything else is session
   * bookkeeping; declare additional names with {@code cacheEnvironment} in the build JSON.
   */
  static final Set<String> BUILD_ENVIRONMENT =
      Set.of(
          "JAVA_HOME",
          "GRAALVM_HOME",
          "JAVA_TOOL_OPTIONS",
          "JDK_JAVA_OPTIONS",
          "_JAVA_OPTIONS",
          "CC",
          "CXX",
          "LD",
          "AR",
          "CFLAGS",
          "CXXFLAGS",
          "CPPFLAGS",
          "LDFLAGS",
          "LIBRARY_PATH",
          "LD_LIBRARY_PATH",
          "DYLD_LIBRARY_PATH",
          "C_INCLUDE_PATH",
          "CPLUS_INCLUDE_PATH",
          "CPATH",
          "USE_NATIVE_IMAGE_JAVA_PLATFORM_MODULE_SYSTEM");

  private BuildCache() {}

  static Result build(BuildConfig config, boolean rebuild, Compiler compiler) throws Exception {
    return build(config, rebuild, compiler, BuildCache::fingerprint);
  }

  static Result build(BuildConfig config, boolean rebuild, Compiler compiler, Identity identity)
      throws Exception {
    if (config.shared) throw new IllegalArgumentException("smh bench requires an executable build");
    return artifact(config, rebuild, compiler, identity);
  }

  static Result artifact(BuildConfig config, boolean rebuild, Compiler compiler, Identity identity)
      throws Exception {
    config.validate();
    Path output = config.output;
    Path root = output.resolve(".smh-cache").toAbsolutePath();
    Files.createDirectories(root);
    // Serialize publication for processes using the same output directory.
    try (var lockFile = new RandomAccessFile(root.resolve("build.lock").toFile(), "rw")) {
      // Closing the file also closes its channel and releases the lock on every exit path.
      lockFile.getChannel().lock();
      Fingerprint fingerprint = identity.fingerprint(config);
      String key = fingerprint.key();
      Path receipt = root.resolve(key + ".json");
      String reason = rebuild ? "rebuild requested" : null;
      if (!rebuild && Files.isRegularFile(receipt)) {
        Path cached = verified(receipt, root);
        if (cached != null) {
          Files.setLastModifiedTime(receipt, java.nio.file.attribute.FileTime.fromMillis(now()));
          return new Result(cached, true, key, "build inputs unchanged");
        }
        reason = "cached artifact failed verification";
      }
      if (reason == null) reason = explain(root, fingerprint);
      Path generation = Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
      try {
        config.output = generation;
        Path binary = compiler.build(config);
        config.output = output;
        if (!key.equals(identity.fingerprint(config).key()))
          throw new IOException(
              "Build inputs changed during compilation; cache entry not published");
        JsonObject record = new JsonObject();
        if (!binary.toAbsolutePath().startsWith(generation) || !Files.isRegularFile(binary))
          throw new IOException("Compiler did not produce the cache artifact");
        record.addProperty("key", key);
        record.addProperty("binary", root.relativize(binary.toAbsolutePath()).toString());
        record.add("files", artifacts(generation, root));
        JsonObject components = new JsonObject();
        fingerprint.components().forEach(components::addProperty);
        record.add("components", components);
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
        prune(root, key);
        return new Result(binary, false, key, reason);
      } finally {
        config.output = output;
      }
    }
  }

  private static long now() {
    return System.currentTimeMillis();
  }

  /** Names the inputs that differ from the most recent published receipt, if any. */
  static String explain(Path root, Fingerprint current) throws IOException {
    JsonObject previous = null;
    long newest = Long.MIN_VALUE;
    for (Path receipt : receipts(root)) {
      if (receipt.getFileName().toString().equals(current.key() + ".json")) continue;
      long modified = Files.getLastModifiedTime(receipt).toMillis();
      if (modified < newest) continue;
      try {
        JsonObject record = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
        if (!record.has("components")) continue;
        previous = record.getAsJsonObject("components");
        newest = modified;
      } catch (RuntimeException malformed) {
        // Unreadable receipts are already misses; they cannot explain this one.
      }
    }
    if (previous == null) return "no previous build";
    SortedSet<String> changed = new TreeSet<>();
    for (var entry : current.components().entrySet()) {
      JsonElement before = previous.get(entry.getKey());
      if (before == null || !before.getAsString().equals(entry.getValue()))
        changed.add(entry.getKey());
    }
    for (String name : previous.keySet())
      if (!current.components().containsKey(name)) changed.add(name);
    if (changed.isEmpty()) return "cache key format changed";
    List<String> shown = new ArrayList<>(changed).subList(0, Math.min(6, changed.size()));
    String more = changed.size() > shown.size() ? " +" + (changed.size() - shown.size()) : "";
    return "changed: " + String.join(", ", shown) + more;
  }

  static List<Path> receipts(Path root) throws IOException {
    try (var files = Files.list(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(file -> file.getFileName().toString().matches("[0-9a-f]{64}\\.json"))
          .sorted()
          .toList();
    }
  }

  /** Keeps the newest receipts and the generations they reference; everything else is removed. */
  static void prune(Path root, String keep) throws IOException {
    List<Path> receipts = new ArrayList<>(receipts(root));
    Map<Path, Long> modified = new HashMap<>();
    for (Path receipt : receipts)
      modified.put(receipt, Files.getLastModifiedTime(receipt).toMillis());
    receipts.sort(Comparator.comparing((Path receipt) -> modified.get(receipt)).reversed());
    Set<String> referenced = new HashSet<>();
    int retained = 0;
    for (Path receipt : receipts) {
      boolean current = receipt.getFileName().toString().equals(keep + ".json");
      if (!current && retained >= RETAINED_GENERATIONS) {
        Files.deleteIfExists(receipt);
        continue;
      }
      retained++;
      try {
        JsonObject record = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
        Path binary = Path.of(record.get("binary").getAsString());
        referenced.add(binary.getName(0).toString());
        for (String file : record.getAsJsonObject("files").keySet())
          referenced.add(Path.of(file).getName(0).toString());
      } catch (RuntimeException malformed) {
        // A malformed receipt references nothing; its generation is unreachable anyway.
      }
    }
    List<Path> generations;
    try (var files = Files.list(root)) {
      generations = files.filter(Files::isDirectory).sorted().toList();
    }
    for (Path generation : generations) {
      if (referenced.contains(generation.getFileName().toString())) continue;
      try {
        deleteTree(generation);
      } catch (IOException busy) {
        // A generation in use (for example a running executable on Windows) is retried next time.
      }
    }
  }

  static void deleteTree(Path directory) throws IOException {
    try (var paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
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

  static Fingerprint fingerprint(BuildConfig c) throws Exception {
    return fingerprint(c, System.getProperty("os.name").startsWith("Windows"));
  }

  static Fingerprint fingerprint(BuildConfig c, boolean windows) throws Exception {
    return fingerprint(c, windows, false);
  }

  static Fingerprint bytecodeFingerprint(BuildConfig c) throws Exception {
    return fingerprint(c, false, true);
  }

  private static Fingerprint fingerprint(BuildConfig c, boolean windows, boolean bytecode)
      throws Exception {
    SortedMap<String, String> components = new TreeMap<>();
    components.put("format", bytecode ? "smh-bytecode-v2" : "smh-build-cache-v3");
    if (!bytecode) {
      components.put("output", digest(c.output.toAbsolutePath().normalize().toString()));
      components.put(
          "options",
          digest(
              c.optimization
                  + "/"
                  + c.pgo
                  + "/"
                  + c.layers
                  + "/"
                  + c.shared
                  + "/"
                  + c.observer
                  + "/"
                  + c.nativeArgs));
    }
    components.put("observer", digest(Objects.toString(c.observer, "")));
    components.put(
        "platform", digest(System.getProperty("os.name") + "/" + System.getProperty("os.arch")));
    components.putAll(environment(System.getenv(), c.cacheEnvironment, windows));
    MessageDigest sources = MessageDigest.getInstance("SHA-256");
    for (Path source : c.sources) {
      if (Files.isDirectory(source)
          && c.output.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize()))
        throw new IOException("Build output must be outside source directories for caching");
      tree(sources, source);
    }
    components.put("sources", hex(sources));
    MessageDigest classpath = MessageDigest.getInstance("SHA-256");
    for (Path dependency : c.classpath) tree(classpath, dependency);
    components.put("classpath", hex(classpath));
    MessageDigest sdk = MessageDigest.getInstance("SHA-256");
    tree(sdk, c.home.resolve("smh.jar"));
    tree(sdk, c.home.resolve("smh-runtime.jar"));
    tree(sdk, c.home.resolve("lib"));
    components.put("sdk", hex(sdk));
    MessageDigest extras = MessageDigest.getInstance("SHA-256");
    for (Path extra : c.cacheInputs) tree(extras, extra);
    components.put("cache-inputs", hex(extras));
    if (!bytecode && c.profile != null) {
      MessageDigest profile = MessageDigest.getInstance("SHA-256");
      tree(profile, c.profile);
      components.put("profile", hex(profile));
    }
    Map<Path, String> hashes = new HashMap<>();
    if (!bytecode) components.put("native-image", compilerIdentity(c.nativeImage, hashes));
    components.put("javac", compilerIdentity(JavaCompiler.command(c.javaHome), hashes));
    // Track the host C compiler as well; additional native dependencies belong in cacheInputs.
    String cc = System.getenv().getOrDefault("CC", "gcc");
    if (!bytecode && !windows) components.put("cc", compilerIdentity(List.of(cc), hashes));
    MessageDigest key = MessageDigest.getInstance("SHA-256");
    for (var entry : components.entrySet()) {
      add(key, entry.getKey());
      add(key, entry.getValue());
    }
    return new Fingerprint(hex(key), components);
  }

  /** Build-affecting variables only: terminal, session, and scratch bookkeeping never matter. */
  static SortedMap<String, String> environment(
      Map<String, String> environment, Collection<String> declared, boolean windows) {
    SortedMap<String, String> result = new TreeMap<>();
    for (var entry : environment.entrySet()) {
      String name = entry.getKey();
      boolean tracked =
          declared.contains(name)
              || BUILD_ENVIRONMENT.contains(windows ? name.toUpperCase(Locale.ROOT) : name);
      if (tracked) result.put("env:" + name, digest(entry.getValue()));
    }
    return result;
  }

  static String environmentIdentity(Map<String, String> environment, Collection<String> declared)
      throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (var entry : environment(environment, declared, false).entrySet()) {
      add(digest, entry.getKey());
      add(digest, entry.getValue());
    }
    return hex(digest);
  }

  static String compilerIdentity(List<String> command) throws Exception {
    return compilerIdentity(command, new HashMap<>());
  }

  /**
   * A launcher hash alone does not identify its compiler implementation. Combine the launcher, the
   * installation's release/configuration files, a metadata manifest of its libraries, and the
   * tool's own version report. Library bytes are not read: a same-size, same-timestamp edit inside
   * a toolchain is not detected, and reinstalling one is a deliberate rebuild.
   */
  static String compilerIdentity(List<String> command, Map<Path, String> hashes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    add(digest, command.subList(1, command.size()).toString());
    Path executable = executable(command.getFirst());
    tree(digest, executable, hashes);
    // Only JDK-shaped (release file) or Elide-shaped (conf directory) installations are walked;
    // a system compiler under /usr/bin must not pull in every library on the host.
    Path installation = executable.toAbsolutePath().getParent().getParent();
    if (Files.isRegularFile(installation.resolve("release"))) {
      tree(digest, installation.resolve("release"), hashes);
      if (Files.isDirectory(installation.resolve("lib")))
        manifest(digest, installation.resolve("lib"));
    } else if (Files.isDirectory(installation.resolve("conf"))) {
      manifest(digest, installation.resolve("conf"));
      if (Files.isDirectory(installation.resolve("lib")))
        manifest(digest, installation.resolve("lib"));
    }
    List<String> probe = new ArrayList<>(command);
    probe.add("--version");
    Process process = new ProcessBuilder(probe).redirectErrorStream(true).start();
    String version = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0)
      throw new IOException("Toolchain identity probe failed: " + command.getFirst());
    add(digest, version);
    return hex(digest);
  }

  /** Relative paths, sizes, and modification times of every regular file under a directory. */
  static void manifest(MessageDigest digest, Path directory) throws IOException {
    add(digest, directory.getFileName().toString());
    try (var paths = Files.walk(directory)) {
      for (Path file : paths.sorted().toList()) {
        BasicFileAttributes attributes =
            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) continue;
        add(digest, directory.relativize(file).toString());
        add(digest, attributes.size() + "/" + attributes.lastModifiedTime().toMillis());
      }
    }
  }

  /**
   * Whether {@code path} is a file this process may execute. Windows treats every file as
   * executable, so excluding directories (rather than requiring a regular file first) keeps each
   * outcome reachable on every platform.
   */
  static boolean executableFile(Path path) {
    return Files.isExecutable(path) && !Files.isDirectory(path);
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
      if (executableFile(candidate)) return candidate.toAbsolutePath();
    }
    throw new IOException("Build tool is not on PATH: " + name);
  }

  private static void tree(MessageDigest digest, Path path) throws Exception {
    tree(digest, path, null);
  }

  private static void tree(MessageDigest digest, Path path, Map<Path, String> hashes)
      throws Exception {
    add(
        digest,
        hashes == null
            ? path.toAbsolutePath().normalize().toString()
            : path.getFileName().toString());
    if (Files.isDirectory(path)) {
      try (var paths = Files.walk(path)) {
        for (Path file : paths.sorted().toList()) {
          if (Files.isSymbolicLink(file) && Files.isDirectory(file))
            throw new IOException(
                "Directory symlinks require an explicit resolved cache input: " + file);
          if (Files.isRegularFile(file)) {
            add(digest, path.relativize(file).toString());
            add(digest, hash(file, hashes));
          }
        }
      }
    } else add(digest, hash(path, hashes));
  }

  private static String hash(Path path, Map<Path, String> hashes) throws Exception {
    if (hashes == null) return hash(path);
    Path key = path.toRealPath();
    String cached = hashes.get(key);
    if (cached != null) return cached;
    String value = hash(path);
    hashes.put(key, value);
    return value;
  }

  static String hash(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return hex(digest);
  }

  static String digest(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      add(digest, value);
      return hex(digest);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException(missing);
    }
  }

  private static String hex(MessageDigest digest) {
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void add(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
