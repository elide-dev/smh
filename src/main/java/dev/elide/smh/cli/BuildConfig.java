// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import dev.elide.smh.runtime.Platform;
import java.nio.file.*;
import java.util.*;

/** Portable build input; paths in JSON are relative to the configuration file. */
public final class BuildConfig {
  public Path home;
  public Path javaHome;
  public Path runtimeJavaHome;
  public Path cremaHost;
  public String execution = "aot";
  public final List<String> runtimeArgs = new ArrayList<>();
  public Path output;
  public final List<Path> sources = new ArrayList<>();
  public final List<Path> classpath = new ArrayList<>();
  public String optimization = "2";
  public String pgo = "off";
  public Path profile;
  public List<String> nativeImage;
  public boolean layers = true;
  public boolean shared;
  public boolean verbose;
  public final List<Path> cacheInputs = new ArrayList<>();
  public String observer;
  public final List<String> nativeArgs = new ArrayList<>();

  public BuildConfig() {
    this(Platform.current());
  }

  public BuildConfig(Platform platform) {
    nativeImage =
        platform.os() == Platform.OperatingSystem.WINDOWS
            ? List.of("cmd.exe", "/d", "/c", "native-image.cmd")
            : List.of("native-image");
  }

  public static BuildConfig read(Path file, Path homeOverride) throws Exception {
    Path base = file.toAbsolutePath().getParent();
    JsonObject j = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    Set<String> allowed =
        Set.of(
            "home",
            "javaHome",
            "runtimeJavaHome",
            "cremaHost",
            "execution",
            "runtimeArgs",
            "output",
            "sources",
            "classpath",
            "optimization",
            "pgo",
            "profile",
            "nativeImage",
            "layers",
            "shared",
            "nativeArgs",
            "observer",
            "cacheInputs");
    for (String key : j.keySet())
      if (!allowed.contains(key))
        throw new IllegalArgumentException("Unknown build option: " + key);
    BuildConfig c = new BuildConfig();
    c.home =
        homeOverride != null
            ? homeOverride.toAbsolutePath()
            : base.resolve(j.has("home") ? j.get("home").getAsString() : "build").normalize();
    c.output =
        base.resolve(j.has("output") ? j.get("output").getAsString() : "build/benchmarks")
            .normalize();
    if (!j.has("sources")) throw new IllegalArgumentException("sources is required");
    j.getAsJsonArray("sources")
        .forEach(v -> c.sources.add(base.resolve(v.getAsString()).normalize()));
    if (j.has("javaHome")) c.javaHome = base.resolve(j.get("javaHome").getAsString()).normalize();
    if (j.has("runtimeJavaHome"))
      c.runtimeJavaHome = base.resolve(j.get("runtimeJavaHome").getAsString()).normalize();
    if (j.has("cremaHost"))
      c.cremaHost = base.resolve(j.get("cremaHost").getAsString()).normalize();
    if (j.has("execution")) c.execution = j.get("execution").getAsString();
    if (j.has("runtimeArgs"))
      j.getAsJsonArray("runtimeArgs").forEach(v -> c.runtimeArgs.add(v.getAsString()));
    if (j.has("classpath"))
      j.getAsJsonArray("classpath")
          .forEach(v -> c.classpath.add(base.resolve(v.getAsString()).normalize()));
    if (j.has("cacheInputs"))
      j.getAsJsonArray("cacheInputs")
          .forEach(v -> c.cacheInputs.add(base.resolve(v.getAsString()).normalize()));
    if (j.has("optimization")) c.optimization = j.get("optimization").getAsString();
    if (j.has("pgo")) c.pgo = j.get("pgo").getAsString();
    if (j.has("profile")) c.profile = base.resolve(j.get("profile").getAsString()).normalize();
    if (j.has("nativeImage")) {
      if (j.get("nativeImage").isJsonArray()) {
        List<String> command = new ArrayList<>();
        j.getAsJsonArray("nativeImage").forEach(v -> command.add(v.getAsString()));
        c.nativeImage = List.copyOf(command);
      } else c.nativeImage = List.of(j.get("nativeImage").getAsString());
    }
    if (j.has("layers")) c.layers = j.get("layers").getAsBoolean();
    if (j.has("shared")) c.shared = j.get("shared").getAsBoolean();
    if (j.has("observer")) c.observer = j.get("observer").getAsString();
    if (j.has("nativeArgs"))
      j.getAsJsonArray("nativeArgs").forEach(v -> c.nativeArgs.add(v.getAsString()));
    return c;
  }

  public void validate() {
    if (!Set.of("aot", "crema", "hotspot", "hotspot-c2").contains(execution))
      throw new IllegalArgumentException("execution must be aot, crema, hotspot or hotspot-c2");
    if (!execution.equals("aot") && (shared || !pgo.equals("off") || observer != null))
      throw new IllegalArgumentException(
          "Runtime-loaded execution does not support shared libraries, PGO or custom observers");
    for (String arg : runtimeArgs)
      if (!(arg.startsWith("-D")
              || arg.startsWith("-Xms")
              || arg.startsWith("-Xmx")
              || arg.startsWith("-XX:"))
          || arg.contains("UseJVMCICompiler")
          || arg.contains("EnableJVMCI")
          || arg.contains("TieredCompilation")
          || arg.contains("JITCompilation")
          || arg.contains("UseCompiler")
          || arg.contains("AOT")
          || arg.contains("SharedArchive"))
        throw new IllegalArgumentException(
            "Unsupported or compiler-changing runtime argument: " + arg);
    if (nativeImage.isEmpty() || nativeImage.stream().anyMatch(String::isBlank))
      throw new IllegalArgumentException("nativeImage must be a nonempty executable/argument list");
    if (!Set.of("0", "1", "2", "3", "b", "s").contains(optimization))
      throw new IllegalArgumentException("Invalid optimization: " + optimization);
    if (!Set.of("off", "instrument", "use").contains(pgo))
      throw new IllegalArgumentException("pgo must be off, instrument or use");
    if (pgo.equals("use") && (profile == null || !Files.isRegularFile(profile)))
      throw new IllegalArgumentException("PGO use requires an existing profile");
    if (!pgo.equals("use") && profile != null)
      throw new IllegalArgumentException("profile requires pgo=use");
    if (sources.isEmpty()) throw new IllegalArgumentException("No sources specified");
    if (!Files.isRegularFile(home.resolve("smh.jar"))
        || !Files.isRegularFile(home.resolve("smh-runtime.jar")))
      throw new IllegalArgumentException(
          "SMH distribution not found at " + home + "; run mise run build or set --home");
    for (Path path : classpath)
      if (!Files.exists(path))
        throw new IllegalArgumentException("Missing classpath entry: " + path);
    for (String arg : nativeArgs)
      if (arg.startsWith("-O")
          || arg.startsWith("--pgo")
          || arg.startsWith("-H:Layer")
          || arg.startsWith("--layer")
          || arg.equals("--shared")
          || arg.equals("-cp")
          || arg.equals("-o")
          || arg.startsWith("-DcompilerBlackholesEnabled")
          || arg.startsWith("-J-DcompilerBlackholesEnabled")
          || arg.startsWith("@"))
        throw new IllegalArgumentException("Use the structured build setting for: " + arg);
  }
}
