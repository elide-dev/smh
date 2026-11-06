// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import java.io.Console;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/** Direct-argv command comparisons: one process per candidate per rotating round. */
final class StartupCandidates {
  private StartupCandidates() {}

  /** Completed build logs survive later clean steps and remain recoverable if publication fails. */
  static final class PreparationFiles {
    final Path directory;
    final Path staging;
    private final JsonObject settings;
    private final List<String> candidates;
    private final List<String> preparations;
    private final Map<Path, Path> logs = new LinkedHashMap<>();
    volatile boolean published;

    PreparationFiles(
        Path directory, JsonObject settings, List<String> candidates, List<String> preparations)
        throws IOException {
      this.directory = directory;
      this.settings = settings;
      this.candidates = candidates;
      this.preparations = preparations;
      staging = Files.createTempDirectory("smh-preparation-");
    }

    synchronized void retain(Path source) throws IOException {
      try (var files = Files.list(source)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          Path saved = staging.resolve(source.getFileName() + "-" + file.getFileName());
          Files.copy(file, saved);
          logs.put(file, saved);
        }
      }
    }

    synchronized void publish() throws IOException {
      if (published) return;
      try {
        Files.createDirectories(directory);
        for (var entry : logs.entrySet()) {
          Files.createDirectories(entry.getKey().getParent());
          Files.copy(entry.getValue(), entry.getKey(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(directory.resolve("commands.json"), candidates.toString());
        Files.writeString(directory.resolve("run.json"), settings.toString());
        Files.writeString(directory.resolve("prepare.json"), preparations.toString());
        published = true;
      } catch (IOException failure) {
        throw new IOException(
            "Could not publish preparation logs; retained at " + staging, failure);
      }
    }

    void shutdown(TerminalDisplay.BuildScope scope) {
      scope.finishBuild();
      try {
        publish();
      } catch (IOException failure) {
        System.err.println("  Preparation logs retained · " + TerminalDisplay.path(staging));
      }
    }

    synchronized void discard() throws IOException {
      try (var files = Files.list(staging)) {
        for (Path file : files.toList()) Files.delete(file);
      }
      Files.delete(staging);
      logs.clear();
    }
  }

  public static final class Arguments implements IParameterConsumer {
    @Override
    public void consumeParameters(Stack<String> args, ArgSpec option, CommandSpec command) {
      List<String> argv = new ArrayList<>();
      while (!args.isEmpty() && !Set.of("--candidate", "--prepare").contains(args.peek()))
        argv.add(args.pop());
      if (argv.isEmpty())
        throw new ParameterException(
            command.commandLine(),
            ((OptionSpec) option).longestName()
                + " needs an executable and optional arguments; put SMH options before commands");
      List<String> candidates = option.getValue();
      candidates.add(new Gson().toJson(argv));
      option.setValue(candidates);
    }
  }

  static int run(List<String> candidates, ExperimentOptions options, TerminalDisplay.Mode display)
      throws Exception {
    return run(candidates, options, display, List.of());
  }

  static int run(
      List<String> candidates,
      ExperimentOptions options,
      TerminalDisplay.Mode display,
      List<String> preparations)
      throws Exception {
    return run(
        candidates,
        options,
        display,
        Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false),
        preparations);
  }

  static int run(
      List<String> candidates,
      ExperimentOptions options,
      TerminalDisplay.Mode display,
      boolean terminal)
      throws Exception {
    return run(candidates, options, display, terminal, List.of());
  }

  static int run(
      List<String> candidates,
      ExperimentOptions options,
      TerminalDisplay.Mode display,
      boolean terminal,
      List<String> preparations)
      throws Exception {
    if (!"startup".equals(options.mode))
      throw new IllegalArgumentException("--candidate requires --mode startup");
    if (candidates.size() < 2)
      throw new IllegalArgumentException("Provide at least two --candidate commands");
    if (options.until != null && !options.until.equals("exit"))
      throw new IllegalArgumentException(
          "Command candidates support --until exit; use smh bench for ready or first-result"
              + " endpoints");
    if (options.backend != null && !options.backend.equals("wall"))
      throw new IllegalArgumentException(
          "Command candidates currently support --backend wall only; use smh bench for Callgrind"
              + " startup");
    options.until = "exit";
    JsonObject settings = options.apply(new JsonObject());
    int launches = options.launches == null ? 10 : options.launches;
    long timeout =
        options.timeout == null ? 30_000_000_000L : ExperimentOptions.nanos(options.timeout);
    Path directory = options.directory();
    settings.addProperty("launches", launches);
    settings.addProperty("timeoutNanos", timeout);
    settings.addProperty("schedule", "rotating-rounds");
    var prepared = new PreparationFiles(directory, settings, candidates, preparations);
    var scope = new TerminalDisplay.BuildScope();
    Thread shutdown = new Thread(() -> prepared.shutdown(scope), "smh-preparation-cleanup");
    Runtime.getRuntime().addShutdownHook(shutdown);
    TerminalDisplay.BUILD_SCOPE.set(scope);
    try {
      for (int i = 0; i < preparations.size(); i++) {
        List<String> command =
            JsonParser.parseString(preparations.get(i)).getAsJsonArray().asList().stream()
                .map(JsonElement::getAsString)
                .toList();
        Path logs = directory.resolve("prepare-" + i);
        BuildPreparation.run(command, Path.of("").toAbsolutePath(), logs, false);
        prepared.retain(logs);
      }
    } finally {
      TerminalDisplay.BUILD_SCOPE.remove();
      BuildLog.removeHook(shutdown);
      prepared.shutdown(scope);
      if (prepared.published) prepared.discard();
    }
    if (!prepared.published)
      throw new IOException(
          "Could not publish preparation metadata; logs retained at " + prepared.staging);
    boolean visual = TerminalDisplay.enabled(display, terminal, System.getenv());
    JsonArray results = new JsonArray();
    List<List<String>> commands = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      List<String> command =
          JsonParser.parseString(candidates.get(i)).getAsJsonArray().asList().stream()
              .map(JsonElement::getAsString)
              .toList();
      commands.add(command);
      JsonObject row = new JsonObject(),
          params = new JsonObject(),
          metric = new JsonObject(),
          smh = new JsonObject();
      row.addProperty("benchmark", "Command startup");
      row.addProperty("mode", "startup");
      params.addProperty(
          "candidate",
          (i + 1)
              + ": "
              + Path.of(command.getFirst()).getFileName()
              + " "
              + String.join(" ", command.subList(1, command.size())));
      row.add("params", params);
      metric.addProperty("score", 0);
      metric.addProperty("scoreUnit", "ms/launch");
      JsonArray raw = new JsonArray();
      raw.add(new JsonArray());
      metric.add("rawData", raw);
      row.add("primaryMetric", metric);
      smh.addProperty("schemaVersion", 1);
      smh.addProperty("experimentMode", "startup");
      smh.addProperty("backend", "wall");
      smh.addProperty("endpoint", "exit");
      smh.addProperty("boundary", "parent-launch-to-process-exit");
      smh.addProperty("schedule", "rotating-rounds");
      smh.addProperty("cacheState", "fresh-process; filesystem-cache-uncontrolled");
      smh.addProperty("accuracy", "unqualified");
      smh.addProperty("workingDirectory", Path.of("").toAbsolutePath().toString());
      smh.add("command", new Gson().toJsonTree(command));
      smh.add("observations", new JsonArray());
      row.add("smh", smh);
      results.add(row);
    }
    try (var live =
        new LiveReport(System.err, TerminalDisplay.width(System.getenv()), visual && terminal)) {
      for (int round = 0; round < launches; round++) {
        for (int slot = 0; slot < commands.size(); slot++) {
          int index = (round + slot) % commands.size();
          Path launchDirectory =
              Files.createDirectories(
                  directory.resolve("candidate-" + index).resolve("launch-" + round));
          var launch =
              StartupExperiment.execute(
                  new ProcessBuilder(commands.get(index)),
                  launchDirectory,
                  timeout,
                  UUID.randomUUID().toString(),
                  true);
          JsonObject row = results.get(index).getAsJsonObject(),
              metric = row.getAsJsonObject("primaryMetric");
          JsonArray scores = metric.getAsJsonArray("rawData").get(0).getAsJsonArray();
          double score = launch.completionNanos() / 1e6;
          scores.add(score);
          metric.addProperty(
              "score",
              scores.asList().stream()
                  .mapToDouble(JsonElement::getAsDouble)
                  .average()
                  .orElseThrow());
          row.addProperty("forks", scores.size());
          JsonObject observation = new JsonObject();
          observation.addProperty("index", round);
          observation.addProperty("round", round);
          observation.addProperty("slot", slot);
          observation.addProperty("processId", launch.processId());
          observation.addProperty("endpointNanos", launch.endpointNanos());
          observation.addProperty("completionNanos", launch.completionNanos());
          observation.addProperty("score", score);
          row.getAsJsonObject("smh").getAsJsonArray("observations").add(observation);
          Files.writeString(launchDirectory.resolve("observation.json"), observation.toString());
          JsonArray snapshot = new JsonArray();
          for (JsonElement entry : results)
            if (entry.getAsJsonObject().has("forks")) snapshot.add(entry.deepCopy());
          Files.writeString(directory.resolve("partial-results.json"), snapshot.toString());
          live.update(
              snapshot,
              "Startup · round "
                  + (round + 1)
                  + "/"
                  + launches
                  + " · candidate "
                  + (slot + 1)
                  + "/"
                  + commands.size());
        }
      }
    }
    Files.writeString(
        directory.resolve("results.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(results));
    JsonObject comparison = new JsonObject();
    comparison.addProperty("schemaVersion", 1);
    comparison.addProperty("method", "observed-arithmetic-means");
    comparison.addProperty("significanceAssessed", false);
    comparison.addProperty("summary", fastest(results));
    Files.writeString(directory.resolve("comparison.json"), comparison.toString());
    if (visual) {
      TerminalDisplay.summary(
          results.toString(), System.err, null, TerminalDisplay.width(System.getenv()));
      System.err.println("  " + fastest(results));
    }
    if (!visual || !terminal) System.out.println(results);
    System.err.println("  Report · " + TerminalDisplay.path(directory.resolve("results.json")));
    return 0;
  }

  static String fastest(JsonArray rows) {
    List<JsonObject> ranked =
        rows.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .sorted(
                Comparator.comparingDouble(
                    row -> row.getAsJsonObject("primaryMetric").get("score").getAsDouble()))
            .toList();
    double first = ranked.getFirst().getAsJsonObject("primaryMetric").get("score").getAsDouble();
    double second = ranked.get(1).getAsJsonObject("primaryMetric").get("score").getAsDouble();
    if (first == second) return "No observed winner · equal means";
    return "Fastest observed · candidate "
        + ranked
            .getFirst()
            .getAsJsonObject("params")
            .get("candidate")
            .getAsString()
            .split(": ", 2)[0]
        + "\n  "
        + ResultDetails.number(second / first)
        + "× vs next · observed means"
        + "\n  significance not assessed";
  }
}
