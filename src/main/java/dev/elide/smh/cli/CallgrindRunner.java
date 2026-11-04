// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import dev.elide.smh.runtime.*;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Launches real Callgrind and imports complete, isolated benchmark-thread event dumps. */
@Singleton
public final class CallgrindRunner {
  static final String REGION =
      "void dev.elide.smh.runtime.SimulationRunner::measureBatch(dev.elide.smh.runtime.BenchmarkCase*,"
          + " long)";

  @FunctionalInterface
  interface Commands {
    int run(List<String> command, Path output, Path error) throws Exception;
  }

  private final Commands commands;
  private final Platform platform;

  public CallgrindRunner() {
    this(CallgrindRunner::execute, Platform.current());
  }

  CallgrindRunner(Commands commands, Platform platform) {
    this.commands = commands;
    this.platform = platform;
  }

  public JsonObject run(Path binary, Path config, Path output, String valgrind) throws Exception {
    if (platform.os() != Platform.OperatingSystem.LINUX)
      throw new IllegalArgumentException("Callgrind execution currently requires a Linux worker");
    RunConfig settings = RunConfig.read(config.toAbsolutePath().toString());
    if (settings.backend != MeasurementBackend.CALLGRIND)
      throw new IllegalArgumentException(
          "simulate requires backend=callgrind in the run configuration");
    if (settings.output != null)
      throw new IllegalArgumentException(
          "simulate owns manifest output; use --output instead of run output");
    binary = binary.toRealPath();
    output = output.toAbsolutePath();
    Files.createDirectories(output.getParent());
    Files.createDirectory(output); // Never import dumps from a previous run.
    Path version = output.resolve("valgrind-version.txt");
    if (commands.run(List.of(valgrind, "--version"), version, output.resolve("version-error.log"))
        != 0) throw new IOException("Valgrind version probe failed; see " + output);
    String simulator = Files.readString(version).strip();
    if (!simulator.startsWith("valgrind-"))
      throw new IOException("Unexpected Valgrind version: " + simulator);
    Path frozenConfig = output.resolve("run.json");
    Path requestedConfig = output.resolve("requested-run.json");
    Files.copy(config, requestedConfig);
    JsonObject effective =
        JsonParser.parseString(Files.readString(requestedConfig)).getAsJsonObject();
    effective.addProperty("output", output.resolve("execution.json").toString());
    Files.writeString(frozenConfig, effective.toString());
    List<String> command =
        List.of(
            valgrind,
            "--tool=callgrind",
            "--collect-atstart=no",
            "--toggle-collect=" + REGION,
            "--dump-after=" + REGION,
            "--separate-threads=yes",
            "--cache-sim=yes",
            "--branch-sim=yes",
            "--I1=32768,8,64",
            "--D1=32768,8,64",
            "--LL=8388608,16,64",
            "--callgrind-out-file=" + output.resolve("callgrind.out.%p"),
            binary.toString(),
            frozenConfig.toString());
    Files.writeString(output.resolve("command.json"), new Gson().toJson(command));
    if (commands.run(
            command, output.resolve("benchmark.stdout.log"), output.resolve("valgrind.log"))
        != 0)
      throw new IOException(
          "Simulation process failed; partial dumps are not results. See " + output);
    JsonArray manifest =
        JsonParser.parseString(Files.readString(output.resolve("execution.json"))).getAsJsonArray();
    JsonObject report = importResults(manifest, output);
    report.addProperty("simulatorVersion", simulator);
    report.addProperty("binarySha256", digest(binary));
    report.addProperty("runConfigSha256", digest(frozenConfig));
    report.addProperty("requestedRunConfigSha256", digest(requestedConfig));
    Path buildManifest = binary.resolveSibling("build.json");
    if (Files.isRegularFile(buildManifest)) {
      JsonObject build = JsonParser.parseString(Files.readString(buildManifest)).getAsJsonObject();
      report.add("build", build);
      report.addProperty("buildManifestSha256", digest(buildManifest));
      report.addProperty(
          "purpose",
          "instrument".equals(build.get("pgo").getAsString())
              ? "instrumented-validation"
              : "exploratory-measurement");
      report.addProperty(
          "buildInformationBinding", "unverified-sidecar; binary hash recorded independently");
    } else report.addProperty("purpose", "unknown-build-exploration");
    report.addProperty("workingDirectory", Path.of("").toAbsolutePath().toString());
    report.addProperty("architecture", platform.architecture().name());
    report.add("command", new Gson().toJsonTree(command));
    Files.writeString(
        output.resolve("results.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
    return report;
  }

  public static JsonObject importResults(JsonArray manifest, Path directory) throws Exception {
    if (manifest.isEmpty()) throw new IllegalArgumentException("Empty simulation manifest");
    List<CallgrindDump> measured = new ArrayList<>();
    JsonArray rawFiles = new JsonArray();
    Set<Integer> pids = new HashSet<>(), threads = new HashSet<>();
    Set<String> identities = new HashSet<>();
    String creator = null;
    Map<String, String> caches = null;
    Set<String> events = null;
    try (var files = Files.list(directory)) {
      for (Path path :
          files
              .filter(p -> p.getFileName().toString().startsWith("callgrind.out."))
              .sorted()
              .toList()) {
        if (Files.size(path) == 0
            && path.getFileName().toString().matches("callgrind\\.out\\.[0-9]+")) continue;
        CallgrindDump dump = CallgrindDump.parse(Files.readString(path));
        if (!identities.add(dump.pid() + ":" + dump.part() + ":" + dump.thread()))
          throw new IllegalArgumentException("Duplicate simulator part/thread");
        pids.add(dump.pid());
        if (creator == null) {
          creator = dump.creator();
          caches = dump.caches();
          events = dump.events().keySet();
        }
        if (!creator.equals(dump.creator())
            || !caches.equals(dump.caches())
            || !events.equals(dump.events().keySet()))
          throw new IllegalArgumentException(
              "Inconsistent simulator producer/cache/event metadata");
        boolean nonzero = dump.events().values().stream().anyMatch(n -> n.signum() != 0);
        if (dump.trigger().equals("--dump-after=" + REGION) && nonzero) {
          if (dump.events().get("Ir").signum() == 0)
            throw new IllegalArgumentException("Region contains no instructions");
          measured.add(dump);
          threads.add(dump.thread());
        } else if (nonzero || !dump.trigger().equals("Program termination")) {
          throw new IllegalArgumentException(
              "Unexpected or nonempty dump outside the measured region");
        }
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", path.getFileName().toString());
        artifact.addProperty("sha256", digest(path));
        rawFiles.add(artifact);
      }
    }
    if (pids.size() != 1 || threads.size() != 1)
      throw new IllegalArgumentException(
          "Missing or ambiguous benchmark-thread dumps; build with -g and -H:-DeleteLocalSymbols");
    measured.sort(Comparator.comparingInt(CallgrindDump::part));
    for (int i = 0; i < measured.size(); i++)
      if (measured.get(i).part() != i + 1)
        throw new IllegalArgumentException("Noncontiguous simulation regions");
    int cursor = 0;
    JsonArray results = new JsonArray();
    for (JsonElement element : manifest) {
      JsonObject result = element.getAsJsonObject().deepCopy();
      if (!"callgrind".equals(result.get("backend").getAsString())
          || !"pending-counter-import".equals(result.get("status").getAsString()))
        throw new IllegalArgumentException("Not a pending SMH Callgrind manifest");
      int iterations = result.get("measurementIterations").getAsBigDecimal().intValueExact();
      BigInteger operations =
          result.get("operationsPerIteration").getAsBigDecimal().toBigIntegerExact();
      if (iterations < 1 || operations.signum() <= 0 || iterations > measured.size() - cursor)
        throw new IllegalArgumentException(
            "Simulation work budget does not match collected regions");
      JsonArray observations = new JsonArray();
      Set<BigInteger> instructions = new HashSet<>();
      BigInteger sum = BigInteger.ZERO;
      for (int i = 0; i < iterations; i++) {
        CallgrindDump dump = measured.get(cursor++);
        JsonObject sample = new JsonObject(), counters = new JsonObject();
        dump.events().forEach((key, value) -> counters.addProperty(key, value.toString()));
        sample.addProperty("index", i);
        sample.addProperty("part", dump.part());
        sample.addProperty("operations", operations.toString());
        sample.add("events", counters);
        observations.add(sample);
        BigInteger ir = dump.events().get("Ir");
        instructions.add(ir);
        sum = sum.add(ir);
      }
      result.addProperty("status", "collected");
      result.addProperty("accuracy", "unqualified");
      result.addProperty("sinkQualification", "unverified");
      result.addProperty(
          "instructionRepeatability",
          iterations == 1 ? "not-assessed" : instructions.size() == 1 ? "identical" : "varies");
      result.add("observations", observations);
      JsonObject metric = new JsonObject();
      BigInteger denominator = operations.multiply(BigInteger.valueOf(iterations));
      metric.addProperty("event", "Ir");
      metric.addProperty("scoreUnit", "instructions/op");
      metric.addProperty("numerator", sum.toString());
      metric.addProperty("denominator", denominator.toString());
      metric.addProperty(
          "score", new BigDecimal(sum).divide(new BigDecimal(denominator), MathContext.DECIMAL128));
      metric.addProperty("rounding", "DECIMAL128 display; numerator/denominator are exact");
      result.add("primaryMetric", metric);
      results.add(result);
    }
    if (cursor != measured.size())
      throw new IllegalArgumentException("Extra simulator regions without manifest entries");
    JsonObject report = new JsonObject();
    report.addProperty("schemaVersion", 1);
    report.addProperty("backend", "callgrind");
    report.addProperty("quantity", "simulated-events");
    report.addProperty("accuracy", "unqualified");
    report.add("availableEvents", new Gson().toJsonTree(new TreeSet<>(events)));
    report.addProperty("creator", creator);
    report.addProperty("thread", threads.iterator().next());
    report.add("cacheModel", new Gson().toJsonTree(caches));
    report.add("rawFiles", rawFiles);
    report.add("results", results);
    return report;
  }

  private static String digest(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      for (int length; (length = input.read(buffer)) != -1; ) digest.update(buffer, 0, length);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static int execute(List<String> command, Path output, Path error) throws Exception {
    return new ProcessBuilder(command)
        .redirectOutput(output.toFile())
        .redirectError(error.toFile())
        .start()
        .waitFor();
  }
}
