// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import dev.elide.smh.runtime.*;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Parent-side launch timing and process-wide simulator snapshots; compilation precedes both. */
final class StartupExperiment {
  static final String BOUNDARY = "void dev.elide.smh.runtime.Startup::reached()";

  record Launch(long endpointNanos, long completionNanos, String result, long processId) {}

  private record Readout(long endpoint, int signals, String lastLine) {}

  private StartupExperiment() {}

  @FunctionalInterface
  interface Launcher {
    Launch execute(ProcessBuilder builder, Path directory, long timeout, String token, boolean exit)
        throws Exception;
  }

  static ProcessBuilder copy(ProcessBuilder template, Path oldConfig, Path config) {
    List<String> command = new ArrayList<>(template.command());
    int index = command.indexOf(oldConfig.toAbsolutePath().toString());
    if (index < 0)
      throw new IllegalArgumentException("Launch command does not contain its run configuration");
    command.set(index, config.toAbsolutePath().toString());
    ProcessBuilder result = new ProcessBuilder(command).directory(template.directory());
    result.environment().clear();
    result.environment().putAll(template.environment());
    result.environment().remove("SMH_RESULT_STREAM");
    return result;
  }

  static Launch execute(
      ProcessBuilder builder, Path directory, long timeout, String token, boolean exit)
      throws Exception {
    Files.writeString(directory.resolve("command.json"), new Gson().toJson(builder.command()));
    builder.redirectError(directory.resolve("stderr.log").toFile());
    builder.environment().put("SMH_STARTUP_TOKEN", token);
    CompletableFuture<Readout> read = new CompletableFuture<>();
    Process child = null;
    Thread shutdown = null;
    long start = System.nanoTime();
    try {
      child = builder.start();
      Process running = child;
      shutdown = new Thread(() -> TerminalDisplay.terminate(running), "smh-startup-cleanup");
      Runtime.getRuntime().addShutdownHook(shutdown);
      Thread.ofVirtual()
          .start(
              () -> {
                try (var input = running.inputReader();
                    var output = Files.newBufferedWriter(directory.resolve("stdout.log"))) {
                  String line, last = "";
                  long endpoint = 0;
                  int signals = 0;
                  while ((line = input.readLine()) != null) {
                    if (line.equals("SMH_STARTUP " + token)) {
                      endpoint = System.nanoTime() - start;
                      signals++;
                    }
                    if (!line.isBlank()) last = line;
                    output.write(line);
                    output.newLine();
                  }
                  read.complete(new Readout(endpoint, signals, last));
                } catch (Throwable error) {
                  read.completeExceptionally(error);
                }
              });
      if (!child.waitFor(timeout, TimeUnit.NANOSECONDS))
        throw new IOException("Startup timed out; partial observations retained at " + directory);
      long completion = System.nanoTime() - start;
      Readout output = read.get(Math.max(1, timeout - completion), TimeUnit.NANOSECONDS);
      if (child.exitValue() != 0) throw new IOException("Startup process failed; see " + directory);
      if (!exit && (output.signals != 1 || output.endpoint <= 0))
        throw new IOException(
            "Startup did not produce exactly one endpoint notification; see " + directory);
      return new Launch(
          exit ? completion : output.endpoint, completion, output.lastLine, child.pid());
    } catch (Exception failure) {
      recordFailure(directory, failure);
      throw failure;
    } finally {
      TerminalDisplay.terminate(child);
      if (shutdown != null) Runtime.getRuntime().removeShutdownHook(shutdown);
    }
  }

  private static void recordFailure(Path directory, Exception failure) throws IOException {
    JsonObject status = new JsonObject();
    status.addProperty("status", "failed");
    status.addProperty("reason", failure.toString());
    Files.writeString(directory.resolve("failure.json"), status.toString());
  }

  static List<String> simulationCommand(
      String valgrind, List<String> command, Path directory, boolean exit) {
    return simulationCommand(valgrind, command, directory, exit, Platform.current());
  }

  static List<String> simulationCommand(
      String valgrind, List<String> command, Path directory, boolean exit, Platform platform) {
    if (platform.os() != Platform.OperatingSystem.LINUX)
      throw new IllegalArgumentException("Startup simulation requires Linux Callgrind");
    List<String> result =
        new ArrayList<>(
            List.of(
                valgrind,
                "--tool=callgrind",
                "--collect-atstart=yes",
                "--separate-threads=no",
                "--cache-sim=yes",
                "--branch-sim=yes",
                "--I1=32768,8,64",
                "--D1=32768,8,64",
                "--LL=8388608,16,64",
                "--callgrind-out-file=" + directory.resolve("callgrind.out.%p")));
    if (!exit) result.add("--dump-before=" + BOUNDARY);
    result.addAll(command);
    return result;
  }

  static JsonObject importLaunch(Path directory, boolean exit) throws Exception {
    List<CallgrindDump> dumps = new ArrayList<>();
    JsonArray files = new JsonArray();
    try (var paths = Files.list(directory)) {
      for (Path file :
          paths
              .filter(p -> p.getFileName().toString().startsWith("callgrind.out."))
              .sorted()
              .toList()) {
        dumps.add(CallgrindDump.parse(Files.readString(file), true));
        JsonObject raw = new JsonObject();
        raw.addProperty("path", file.toString());
        raw.addProperty("sha256", BuildCache.hash(file));
        files.add(raw);
      }
    }
    dumps.sort(Comparator.comparingInt(CallgrindDump::part));
    if (dumps.size() != (exit ? 1 : 2))
      throw new IOException("Missing or extra startup simulator snapshots");
    CallgrindDump first = dumps.getFirst();
    for (int i = 0; i < dumps.size(); i++) {
      CallgrindDump dump = dumps.get(i);
      String trigger = i == 0 && !exit ? "--dump-before=" + BOUNDARY : "Program termination";
      if (dump.part() != i + 1
          || dump.pid() != first.pid()
          || !dump.trigger().equals(trigger)
          || !dump.creator().equals(first.creator())
          || !dump.caches().equals(first.caches())
          || !dump.events().keySet().equals(first.events().keySet()))
        throw new IOException("Inconsistent startup simulator snapshots");
    }
    if (first.events().get("Ir").signum() <= 0)
      throw new IOException("Startup snapshot contains no instructions");
    JsonObject result = new JsonObject();
    result.add("events", counters(first.events()));
    result.add("attributedEvents", counters(first.attributedEvents()));
    result.add("postEndpointEvents", exit ? new JsonObject() : counters(dumps.getLast().events()));
    result.add(
        "postEndpointAttributedEvents",
        exit ? new JsonObject() : counters(dumps.getLast().attributedEvents()));
    result.add("cacheModel", new Gson().toJsonTree(first.caches()));
    result.addProperty("creator", first.creator());
    result.add("rawFiles", files);
    return result;
  }

  private static JsonObject counters(Map<String, BigInteger> events) {
    JsonObject result = new JsonObject();
    events.forEach((key, value) -> result.addProperty(key, value.toString()));
    return result;
  }

  static JsonArray run(
      ProcessBuilder template,
      Path config,
      Path directory,
      String valgrind,
      TerminalDisplay.Mode display,
      BenchmarkComparison comparison)
      throws Exception {
    try {
      return run(
          template, config, directory, valgrind, display, comparison, StartupExperiment::execute);
    } catch (Exception failure) {
      recordFailure(directory, failure);
      throw failure;
    }
  }

  static JsonArray run(
      ProcessBuilder template,
      Path config,
      Path directory,
      String valgrind,
      TerminalDisplay.Mode display,
      BenchmarkComparison comparison,
      Launcher launcher)
      throws Exception {
    RunConfig settings = RunConfig.read(config.toString());
    JsonObject effective = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
    effective.remove("output");
    String until = settings.until == null ? "first-result" : settings.until;
    boolean exit = until.equals("exit"),
        simulation = settings.backend == MeasurementBackend.CALLGRIND;
    boolean terminal = Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false);
    boolean visual = TerminalDisplay.enabled(display, terminal, System.getenv());
    if (comparison != null && !visual)
      throw new IllegalArgumentException("--compare requires a terminal or --display tui");
    long timeout = settings.timeoutNanos == null ? 30_000_000_000L : settings.timeoutNanos;
    int count = settings.launches == null ? 10 : settings.launches;
    Path discovery = Files.createDirectory(directory.resolve("discovery"));
    Path discoveryConfig = Files.writeString(discovery.resolve("run.json"), effective.toString());
    ProcessBuilder probe = copy(template, config, discoveryConfig);
    probe.environment().put("SMH_STARTUP_DISCOVER", "1");
    Launch discovered =
        launcher.execute(probe, discovery, timeout, UUID.randomUUID().toString(), true);
    JsonArray cases = JsonParser.parseString(discovered.result).getAsJsonArray();
    if (cases.isEmpty()) throw new IOException("Startup discovery returned no cases");
    JsonArray results = new JsonArray();
    int caseIndex = 0;
    for (JsonElement value : cases) {
      JsonObject description = value.getAsJsonObject();
      Path caseDirectory = Files.createDirectory(directory.resolve("case-" + caseIndex++));
      JsonObject selected = effective.deepCopy();
      selected.addProperty(
          "include", "^" + Pattern.quote(description.get("benchmark").getAsString()) + "$");
      JsonObject params = new JsonObject();
      description
          .getAsJsonObject("params")
          .entrySet()
          .forEach(
              entry -> {
                JsonArray values = new JsonArray();
                values.add(entry.getValue());
                params.add(entry.getKey(), values);
              });
      selected.add("params", params);
      JsonArray observations = new JsonArray(), scores = new JsonArray();
      JsonObject model = null;
      BigDecimal sum = BigDecimal.ZERO;
      for (int i = 0; i < count; i++) {
        Path launchDirectory = Files.createDirectory(caseDirectory.resolve("launch-" + i));
        Path run = Files.writeString(launchDirectory.resolve("run.json"), selected.toString());
        ProcessBuilder process = copy(template, config, run);
        process.environment().remove("SMH_STARTUP_DISCOVER");
        if (simulation)
          process.command(simulationCommand(valgrind, process.command(), launchDirectory, exit));
        Launch launch =
            launcher.execute(process, launchDirectory, timeout, UUID.randomUUID().toString(), exit);
        JsonArray child = JsonParser.parseString(launch.result).getAsJsonArray();
        if (child.size() != 1 || !child.get(0).equals(description))
          throw new IOException("Startup child identity does not match discovery");
        JsonObject observation;
        BigDecimal score;
        if (simulation) {
          observation = importLaunch(launchDirectory, exit);
          JsonObject currentModel = new JsonObject();
          currentModel.add("cacheModel", observation.get("cacheModel"));
          currentModel.add("creator", observation.get("creator"));
          currentModel.addProperty(
              "counterPolicy", "process-summary-including-unattributed-events");
          currentModel.addProperty("architecture", System.getProperty("os.arch"));
          if (model != null && !model.equals(currentModel))
            throw new IOException("Startup simulator model changed between launches");
          model = currentModel;
          score = new BigDecimal(observation.getAsJsonObject("events").get("Ir").getAsString());
        } else {
          observation = new JsonObject();
          observation.addProperty("endpointNanos", launch.endpointNanos);
          observation.addProperty("completionNanos", launch.completionNanos);
          score = BigDecimal.valueOf(launch.endpointNanos, 6);
        }
        observation.addProperty("processId", launch.processId);
        observation.addProperty("index", i);
        observation.addProperty("score", score);
        observations.add(observation);
        scores.add(score);
        sum = sum.add(score);
        Files.writeString(launchDirectory.resolve("observation.json"), observation.toString());
        System.err.println(
            "Startup · "
                + TerminalDisplay.clean(description.get("benchmark").getAsString())
                + " · launch "
                + (i + 1)
                + "/"
                + count);
      }
      JsonObject row = description.deepCopy(), metric = new JsonObject(), smh = new JsonObject();
      row.addProperty("mode", "startup");
      row.addProperty("forks", count);
      metric.addProperty("score", sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128));
      metric.addProperty("scoreUnit", simulation ? "instructions/launch" : "ms/launch");
      JsonArray raw = new JsonArray();
      raw.add(scores);
      metric.add("rawData", raw);
      row.add("primaryMetric", metric);
      smh.addProperty("schemaVersion", 1);
      smh.addProperty("backend", simulation ? "callgrind" : "wall");
      smh.addProperty("experimentMode", "startup");
      smh.addProperty("endpoint", until);
      smh.addProperty(
          "boundary",
          simulation
              ? "guest-process-entry-to-endpoint-all-threads"
              : exit ? "parent-launch-to-process-exit" : "parent-launch-to-notification-receipt");
      smh.addProperty("cacheState", "fresh-process; filesystem-cache-uncontrolled");
      smh.addProperty("accuracy", "unqualified");
      smh.add("observations", observations);
      if (model != null) smh.add("simulationModel", model);
      String identity = template.environment().get("SMH_EXECUTION_IDENTITY");
      if (identity != null) smh.add("execution", JsonParser.parseString(identity));
      row.add("smh", smh);
      results.add(row);
      Files.writeString(caseDirectory.resolve("result.json"), row.toString());
      if (visual)
        TerminalDisplay.summary(
            new Gson().toJson(List.of(row)),
            System.err,
            comparison,
            TerminalDisplay.width(System.getenv()));
    }
    String json = new GsonBuilder().setPrettyPrinting().create().toJson(results);
    Files.writeString(directory.resolve("results.json"), json);
    if (settings.output != null) {
      Files.createDirectories(settings.output.toAbsolutePath().getParent());
      Files.writeString(settings.output, json);
    }
    if (!visual || !terminal) System.out.println(json);
    System.err.println(
        "Startup report: " + TerminalDisplay.path(directory.resolve("results.json")));
    return results;
  }
}
