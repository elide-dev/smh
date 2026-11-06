// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Launch a fresh reusable host with benchmark bytecode kept off its own classpath. */
final class RuntimeExecution {
  private RuntimeExecution() {}

  static ProcessBuilder aot(BuildCache.Result artifact, Path config) throws Exception {
    ProcessBuilder process =
        new ProcessBuilder(artifact.binary().toString(), config.toAbsolutePath().toString());
    JsonObject identity = new JsonObject();
    identity.addProperty("harness", "smh");
    identity.addProperty("backend", "aot");
    identity.addProperty("purpose", "measurement");
    identity.addProperty("buildKey", artifact.key());
    identity.addProperty("hostSha256", BuildCache.hash(artifact.binary()));
    identity.add("runtimeArgs", new JsonArray());
    Path manifest = artifact.binary().getParent().resolve("build.json");
    // An image without SMH's build manifest beside it was built elsewhere; say so in every row.
    identity.addProperty("provenance", Files.isRegularFile(manifest) ? "smh-build" : "external");
    if (Files.isRegularFile(manifest)) {
      JsonObject build = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
      identity.add("hostBuild", build);
      if (new JsonPrimitive("instrument").equals(build.get("pgo")))
        identity.addProperty("purpose", "training");
    }
    process.environment().put("SMH_EXECUTION_IDENTITY", identity.toString());
    return process;
  }

  /** An executable SMH did not build: identified by content, never by a cache receipt. */
  static BuildCache.Result external(Path binary) throws Exception {
    if (!BuildCache.executableFile(binary))
      throw new IllegalArgumentException("Benchmark binary is not an executable file: " + binary);
    Path absolute = binary.toAbsolutePath().normalize();
    return new BuildCache.Result(
        absolute,
        true,
        "external-" + BuildCache.hash(absolute).substring(0, 12),
        "external binary");
  }

  static ProcessBuilder prepare(BuildConfig c, Path config) throws Exception {
    c.validate();
    List<String> command = new ArrayList<>();
    JsonObject identity = new JsonObject();
    identity.addProperty("harness", "smh");
    identity.addProperty("backend", c.execution);
    identity.addProperty(
        "purpose",
        c.runtimeArgs.stream().anyMatch(arg -> arg.matches("-XX:.*(Trace|Print|Log).*"))
            ? "diagnostic"
            : "measurement");
    identity.addProperty("jitCompilation", "unverified");
    identity.addProperty("invocationBoundary", "host-interface-to-runtime-loaded-adapter");
    JsonArray runtimeArgs = new JsonArray();
    c.runtimeArgs.forEach(runtimeArgs::add);
    identity.add("runtimeArgs", runtimeArgs);
    Path executable;
    String executableDigest;
    if (c.execution.equals("crema")) {
      executable =
          c.cremaHost == null
              ? c.home.resolve("crema").resolve(Platform.current().executable("smh-crema"))
              : c.cremaHost;
      Path receipt = Path.of(executable + ".json");
      if (!Files.isRegularFile(receipt))
        throw new IOException(
            "Missing Crema host manifest: "
                + receipt
                + "; build the host with tools/build.py crema");
      JsonObject host = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
      executableDigest = BuildCache.hash(executable);
      if (!host.get("sha256").getAsString().equals(executableDigest))
        throw new IOException("Crema host digest does not match its manifest");
      JsonObject files = host.getAsJsonObject("files");
      if (files == null || !files.has(executable.getFileName().toString()))
        throw new IOException("Crema host manifest is missing its native file inventory");
      Path hostDirectory = executable.toAbsolutePath().normalize().getParent();
      for (var entry : files.entrySet()) {
        Path file = hostDirectory.resolve(entry.getKey()).normalize();
        if (!hostDirectory.equals(file.getParent())
            || !entry
                .getValue()
                .getAsString()
                .equals(
                    file.getFileName().equals(executable.getFileName())
                        ? executableDigest
                        : BuildCache.hash(file)))
          throw new IOException("Crema native file mismatch: " + entry.getKey());
      }
      if (!host.get("runtimeSha256")
          .getAsString()
          .equals(BuildCache.hash(c.home.resolve("smh-runtime.jar"))))
        throw new IOException("Crema host was built with a different SMH runtime");
      for (Path dependency : BytecodeBuilder.runtime(c)) {
        JsonElement expected =
            host.getAsJsonObject("dependencies").get(dependency.getFileName().toString());
        if (expected == null || !expected.getAsString().equals(BuildCache.hash(dependency)))
          throw new IOException("Crema host dependency mismatch: " + dependency.getFileName());
      }
      JsonArray flags = host.getAsJsonArray("flags");
      if (!flags.contains(new JsonPrimitive("-H:+RuntimeClassLoading"))
          || !flags.contains(new JsonPrimitive("-H:+GraalJITCompileAtRuntime")))
        throw new IOException("Host manifest does not enable Crema and Ristretto");
      identity.add("hostBuild", host);
      identity.addProperty("compilerRequested", "ristretto");
      command.add(executable.toAbsolutePath().toString());
      command.addAll(c.runtimeArgs);
    } else if (Set.of("hotspot", "hotspot-c2").contains(c.execution)) {
      Path home = c.runtimeJavaHome == null ? c.javaHome : c.runtimeJavaHome;
      if (home == null)
        throw new IllegalArgumentException("hotspot-c2 requires runtimeJavaHome (or javaHome)");
      executable = home.resolve("bin").resolve(Platform.current().executable("java"));
      executableDigest = BuildCache.hash(executable);
      command.add(executable.toAbsolutePath().toString());
      command.addAll(c.runtimeArgs);
      // UseJVMCICompiler is a product flag on GraalVM-based JDKs and an experimental one on
      // plain OpenJDK builds; unlocking first lets both disable the JVMCI top tier.
      for (String flag : List.of("-XX:+UnlockExperimentalVMOptions", "-XX:-UseJVMCICompiler")) {
        command.add(flag);
        runtimeArgs.add(flag);
      }
      if (c.execution.equals("hotspot-c2")) {
        command.add("-XX:-TieredCompilation");
        runtimeArgs.add("-XX:-TieredCompilation");
      }
      identity.addProperty("compilerRequested", c.execution.equals("hotspot") ? "tiered-c2" : "c2");
      identity.addProperty(
          "toolchain", BuildCache.compilerIdentity(List.of(executable.toString())));
      command.addAll(
          List.of(
              "-cp",
              BytecodeBuilder.classpath(BytecodeBuilder.runtime(c)),
              "dev.elide.smh.runtime.BenchmarkHost"));
    } else {
      throw new IllegalArgumentException("Not a runtime-loaded execution backend: " + c.execution);
    }
    BytecodeBuilder.Artifact bytecode =
        BytecodeBuilder.prepare(
            c, false, (arguments, cwd) -> AotBuilder.runCommand(arguments, cwd, c.verbose));
    command.add(config.toAbsolutePath().toString());
    command.add(bytecode.classes().toString());
    c.classpath.forEach(p -> command.add(p.toAbsolutePath().toString()));
    identity.addProperty("payloadKey", bytecode.key());
    identity.addProperty("hostSha256", executableDigest);
    JsonArray arguments = new JsonArray();
    command.forEach(arguments::add);
    identity.add("command", arguments);
    ProcessBuilder process = new ProcessBuilder(command);
    process.environment().put("SMH_EXECUTION_IDENTITY", identity.toString());
    return process;
  }
}
