// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import java.io.Console;
import java.nio.file.*;
import java.util.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/** Direct-argv command comparisons: one process per candidate per rotating round. */
final class StartupCandidates {
  private StartupCandidates() {}

  public static final class Arguments implements IParameterConsumer {
    @Override
    public void consumeParameters(Stack<String> args, ArgSpec option, CommandSpec command) {
      List<String> argv = new ArrayList<>();
      while (!args.isEmpty() && !args.peek().equals("--candidate")) argv.add(args.pop());
      if (argv.isEmpty())
        throw new ParameterException(
            command.commandLine(),
            "--candidate needs an executable and optional arguments; put SMH options before"
                + " candidates");
      List<String> candidates = option.getValue();
      candidates.add(new Gson().toJson(argv));
      option.setValue(candidates);
    }
  }

  static int run(List<String> candidates, ExperimentOptions options, TerminalDisplay.Mode display)
      throws Exception {
    return run(
        candidates,
        options,
        display,
        Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false));
  }

  static int run(
      List<String> candidates,
      ExperimentOptions options,
      TerminalDisplay.Mode display,
      boolean terminal)
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
    Files.writeString(directory.resolve("commands.json"), candidates.toString());
    settings.addProperty("launches", launches);
    settings.addProperty("timeoutNanos", timeout);
    settings.addProperty("schedule", "rotating-rounds");
    Files.writeString(directory.resolve("run.json"), settings.toString());
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
