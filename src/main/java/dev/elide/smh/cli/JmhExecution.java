// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import dev.elide.smh.runtime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Delegation keeps JMH measurements and Leyden training in separate processes and files. */
final class JmhExecution {
  private JmhExecution() {}

  static List<String> arguments(RunConfig run, int forks) {
    run.validate();
    if (!run.mode.equals("steady"))
      throw new IllegalArgumentException(
          "JMH delegation supports steady mode; use smh bench for startup/soak");
    if (run.backend != MeasurementBackend.WALL
        || run.wallBatchSize != 1
        || run.batchCalibration != null
        || run.warmupStability != null
        || run.measurementStability != null)
      throw new IllegalArgumentException(
          "JMH requires fixed wall iterations; SMH batch/stability policies cannot be translated");
    if (forks < 1)
      throw new IllegalArgumentException("JMH measurements require at least one fresh fork");
    List<String> args =
        new ArrayList<>(
            List.of(
                run.include,
                "-f",
                Integer.toString(forks),
                "-wf",
                "0",
                "-t",
                "1",
                "-foe",
                "true",
                "-rf",
                "json"));
    if (run.warmupIterations != null) args.addAll(List.of("-wi", run.warmupIterations.toString()));
    if (run.measurementIterations != null)
      args.addAll(List.of("-i", run.measurementIterations.toString()));
    if (run.warmupNanos != null) args.addAll(List.of("-w", run.warmupNanos + "ns"));
    if (run.measurementNanos != null) args.addAll(List.of("-r", run.measurementNanos + "ns"));
    // Limit this adapter to modes retaining per-iteration scores, including annotation defaults.
    if (run.modes != null) args.addAll(List.of("-bm", String.join(",", run.modes)));
    run.params.forEach(
        (key, values) -> {
          if (key.contains("=") || values.stream().anyMatch(v -> v.contains(",")))
            throw new IllegalArgumentException(
                "JMH parameter names cannot contain '=' and values cannot contain ','");
          args.addAll(List.of("-p", key + "=" + String.join(",", values)));
        });
    return args;
  }

  static JsonObject job(List<String> arguments, List<String> flags, Path raw, boolean training) {
    List<String> args = new ArrayList<>(arguments);
    if (training) args.set(args.indexOf("-f") + 1, "0");
    args.addAll(List.of("-rff", raw.toString()));
    JsonObject result = new JsonObject();
    result.add("arguments", new Gson().toJsonTree(args));
    result.add("runtimeArgs", new Gson().toJsonTree(flags));
    return result;
  }

  static ProcessBuilder process(Path java, String classpath, List<String> flags, Path job) {
    List<String> args = new ArrayList<>(List.of(java.toString()));
    args.addAll(flags);
    args.addAll(List.of("-cp", classpath, "dev.elide.smh.jmh.JmhJob", job.toString()));
    return new ProcessBuilder(args);
  }

  static JsonArray adapt(JsonArray raw, JsonObject identity) {
    if (raw.isEmpty()) throw new IllegalArgumentException("JMH returned no benchmark results");
    JsonArray rows = raw.deepCopy();
    for (JsonElement element : rows) {
      JsonObject row = element.getAsJsonObject();
      if (!JmhBuilder.VERSION.equals(row.get("jmhVersion").getAsString())
          || !Set.of("thrpt", "avgt", "ss").contains(row.get("mode").getAsString())
          || !row.getAsJsonObject("primaryMetric").has("rawData"))
        throw new IllegalArgumentException("Unsupported JMH version or result mode");
      if (!row.has("params")) row.add("params", new JsonObject());
      JsonObject smh = new JsonObject();
      smh.addProperty("backend", "wall");
      smh.addProperty("accuracy", "unqualified");
      smh.addProperty("jitQuiescence", "unknown");
      smh.addProperty("observation", "JMH fork/iteration scores");
      smh.add("execution", identity.deepCopy());
      row.add("smh", smh);
    }
    return rows;
  }

  static void verifyCache(Path output, JsonArray rows, Path cache) throws IOException {
    StringBuilder logs = new StringBuilder();
    try (var paths = Files.list(output)) {
      for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".log")).toList())
        logs.append(Files.readString(file));
    }
    long forks =
        rows.asList().stream()
            .mapToLong(row -> row.getAsJsonObject().get("forks").getAsLong())
            .sum();
    long opened =
        logs.toString()
            .lines()
            .filter(line -> line.contains("Opened AOT cache " + cache + "."))
            .count();
    long linked =
        logs.toString()
            .lines()
            .filter(line -> line.contains("initial full module graph: enabled"))
            .count();
    if (opened != forks
        || linked != forks
        || logs.toString().matches("(?s).*\\[error\\]\\[aot[^]]*].*"))
      throw new IOException(
          "Leyden cache use was not verified in every JMH fork; raw results and logs retained, no"
              + " performance report published");
  }

  static int run(
      BuildConfig c,
      RunConfig run,
      int forks,
      boolean aotCache,
      Path output,
      TerminalDisplay.Mode display,
      BenchmarkComparison comparison)
      throws Exception {
    return run(
        c,
        run,
        forks,
        aotCache,
        output,
        display,
        comparison,
        (command, cwd) -> AotBuilder.runCommand(command, cwd, c.verbose),
        TerminalDisplay::build,
        Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false),
        System.getenv());
  }

  @FunctionalInterface
  interface Executor {
    int execute(ProcessBuilder process, String title, Path directory, boolean verbose)
        throws Exception;
  }

  static int run(
      BuildConfig c,
      RunConfig run,
      int forks,
      boolean aotCache,
      Path output,
      TerminalDisplay.Mode display,
      BenchmarkComparison comparison,
      AotBuilder.Commands compiler,
      Executor executor,
      boolean terminal,
      Map<String, String> environment)
      throws Exception {
    List<String> arguments = arguments(run, forks);
    c.validate();
    if (!Set.of("hotspot", "hotspot-c2").contains(c.execution))
      throw new IllegalArgumentException(
          "Delegated JMH requires execution=hotspot or hotspot-c2; use smh bench for Crema/AOT");
    if (aotCache && !c.execution.equals("hotspot"))
      throw new IllegalArgumentException(
          "Leyden method profiles require the tiered hotspot backend");
    for (String variable :
        List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS"))
      if (!environment.getOrDefault(variable, "").isBlank())
        throw new IllegalArgumentException("Set JVM options through runtimeArgs, not " + variable);
    Path home = c.runtimeJavaHome == null ? c.javaHome : c.runtimeJavaHome;
    if (home == null)
      throw new IllegalArgumentException("JMH requires runtimeJavaHome or javaHome");
    boolean visual = TerminalDisplay.enabled(display, terminal, environment);
    if (comparison != null && !visual)
      throw new IllegalArgumentException("--compare requires a terminal or --display tui");
    Path java = home.resolve("bin").resolve(Platform.current().executable("java")).toAbsolutePath();
    Path jobDirectory = output.toAbsolutePath().normalize();
    if (run.output != null && run.output.toAbsolutePath().normalize().startsWith(jobDirectory))
      throw new IllegalArgumentException(
          "Run output must be outside the JMH job directory; results.json is written"
              + " automatically");
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.createDirectory(output);
    output = output.toAbsolutePath();
    System.err.println("JMH job files: " + TerminalDisplay.path(output));
    BuildCache.Result payload = JmhBuilder.prepare(c, compiler);
    List<Path> cp = new ArrayList<>(List.of(c.home.resolve("smh-jmh.jar"), payload.binary()));
    cp.addAll(JmhBuilder.libraries(c));
    try (var paths = Files.list(c.home.resolve("lib"))) {
      paths.filter(p -> p.getFileName().toString().startsWith("gson-")).sorted().forEach(cp::add);
    }
    cp.addAll(c.classpath);
    if (aotCache && cp.stream().anyMatch(p -> !Files.isRegularFile(p)))
      throw new IllegalArgumentException("Leyden requires a JAR-only classpath");
    String classpath = BytecodeBuilder.classpath(cp);
    List<String> flags = new ArrayList<>(c.runtimeArgs);
    flags.add("-XX:-UseJVMCICompiler");
    flags.add("-XX:-EnableJVMCI");
    flags.add(c.execution.equals("hotspot") ? "-XX:+TieredCompilation" : "-XX:-TieredCompilation");
    JsonObject identity = new JsonObject();
    identity.addProperty("harness", "jmh");
    identity.addProperty("harnessVersion", JmhBuilder.VERSION);
    identity.addProperty("backend", c.execution);
    identity.addProperty("compilerRequested", c.execution.equals("hotspot") ? "tiered-c2" : "c2");
    identity.addProperty("jitCompilation", "unverified");
    identity.addProperty("invocationBoundary", "jmh-generated-harness");
    identity.addProperty(
        "purpose",
        flags.stream().anyMatch(s -> s.matches("-XX:.*(Trace|Print|Log).*"))
            ? "diagnostic"
            : "measurement");
    identity.addProperty("payloadKey", payload.key());
    identity.addProperty("toolchain", BuildCache.compilerIdentity(List.of(java.toString())));
    JsonObject jars = new JsonObject();
    for (Path jar : cp)
      jars.addProperty(
          jar.toString(),
          Files.isRegularFile(jar) ? BuildCache.hash(jar) : "directory-in-payload-key");
    identity.add("classpath", jars);
    Path requiredCache = null;
    if (aotCache) {
      Path original = c.output;
      BuildCache.Result cache;
      JsonObject training = identity.deepCopy();
      training.addProperty("purpose", "training");
      training.addProperty("forks", 0);
      training.add("arguments", new Gson().toJsonTree(arguments));
      training.add("runtimeArgs", new Gson().toJsonTree(flags));
      String trainingIdentity = training.toString();
      try {
        c.output = c.home.resolve("jmh-aot");
        cache =
            BuildCache.artifact(
                c,
                false,
                settings -> {
                  Path artifact = settings.output.resolve("hotspot.aot");
                  List<String> trainingFlags = new ArrayList<>(flags);
                  trainingFlags.add("-XX:AOTCacheOutput=" + artifact);
                  Path job = settings.output.resolve("training-job.json");
                  Files.writeString(
                      job,
                      job(
                              arguments,
                              trainingFlags,
                              settings.output.resolve("training-results.json"),
                              true)
                          .toString());
                  if (executor.execute(
                              process(java, classpath, trainingFlags, job),
                              "Leyden training",
                              settings.output,
                              c.verbose)
                          != 0
                      || !Files.isRegularFile(artifact))
                    throw new IOException(
                        "Leyden training failed to produce an AOT cache; see training logs");
                  Files.writeString(settings.output.resolve("identity.json"), trainingIdentity);
                  return artifact;
                },
                settings ->
                    BuildCache.Fingerprint.of(
                        HexFormat.of()
                            .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                    .digest(
                                        (BuildCache.bytecodeFingerprint(settings).key()
                                                + trainingIdentity
                                                + BuildCache.compilerIdentity(
                                                    List.of(java.toString()))
                                                + BuildCache.hash(c.home.resolve("smh-jmh.jar")))
                                            .getBytes(StandardCharsets.UTF_8)))));
      } finally {
        c.output = original;
      }
      System.err.println(cache.hit() ? "Leyden cache hit" : "Leyden cache created");
      requiredCache = cache.binary();
      flags.add("-XX:AOTMode=on");
      flags.add("-Xlog:aot=info");
      flags.add("-XX:AOTCache=" + cache.binary());
      identity.addProperty("aotCacheKey", cache.key());
      identity.addProperty("aotCacheSha256", BuildCache.hash(cache.binary()));
      identity.addProperty("training", cache.binary().getParent().toString());
    }
    identity.add("runtimeArgs", new Gson().toJsonTree(flags));
    Path raw = output.resolve("jmh-results.json"), job = output.resolve("job.json");
    Files.writeString(job, job(arguments, flags, raw, false).toString());
    Files.writeString(output.resolve("execution.json"), identity.toString());
    int status =
        executor.execute(
            process(java, classpath, List.of(), job), "JMH · " + c.execution, output, c.verbose);
    if (status != 0) return status;
    JsonArray rows =
        adapt(JsonParser.parseString(Files.readString(raw)).getAsJsonArray(), identity);
    if (requiredCache != null) verifyCache(output, rows, requiredCache);
    String json = new GsonBuilder().setPrettyPrinting().create().toJson(rows);
    Files.writeString(output.resolve("results.json"), json);
    if (run.output != null) Files.writeString(run.output, json);
    if (visual)
      TerminalDisplay.summary(json, System.err, comparison, TerminalDisplay.width(environment));
    if (!visual || !terminal) System.out.println(json);
    return 0;
  }
}
