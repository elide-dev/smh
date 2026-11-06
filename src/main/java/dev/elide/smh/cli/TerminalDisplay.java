// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Parent-process presentation. Never called from a measured benchmark region. */
final class TerminalDisplay {
  enum Mode {
    auto,
    plain,
    tui
  }

  static final String CYAN = rgb(105, 222, 231),
      VIOLET = rgb(181, 157, 255),
      GREEN = rgb(131, 224, 174),
      ROSE = rgb(255, 143, 171),
      GOLD = rgb(241, 204, 132),
      TEXT = rgb(222, 230, 243),
      DIM = rgb(130, 147, 172),
      EDGE = rgb(62, 78, 104),
      RESET = "\033[0m",
      BOLD = "\033[1m";
  private static final String FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

  private TerminalDisplay() {}

  static boolean enabled(Mode mode, boolean terminal, Map<String, String> env) {
    return mode == Mode.tui
        || (mode == Mode.auto
            && terminal
            && !env.containsKey("NO_COLOR")
            && !env.containsKey("CI")
            && !"dumb".equals(env.get("TERM")));
  }

  static int width(Map<String, String> environment) {
    try {
      return Math.clamp(Integer.parseInt(environment.getOrDefault("COLUMNS", "80")), 40, 160);
    } catch (NumberFormatException ignored) {
      return 80;
    }
  }

  static int run(
      ProcessBuilder builder,
      String title,
      Mode mode,
      boolean results,
      BenchmarkComparison comparison)
      throws Exception {
    return run(
        builder,
        title,
        mode,
        results,
        Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false),
        System.getenv(),
        System.out,
        System.err,
        comparison);
  }

  static int run(
      ProcessBuilder builder,
      String title,
      Mode mode,
      boolean results,
      boolean terminal,
      Map<String, String> environment,
      PrintStream out,
      PrintStream err)
      throws Exception {
    return run(builder, title, mode, results, terminal, environment, out, err, null);
  }

  static int run(
      ProcessBuilder builder,
      String title,
      Mode mode,
      boolean results,
      boolean terminal,
      Map<String, String> environment,
      PrintStream out,
      PrintStream err,
      BenchmarkComparison comparison)
      throws Exception {
    if (!enabled(mode, terminal, environment)) {
      if (comparison != null)
        throw new IllegalArgumentException("--compare requires a terminal or --display tui");
      return builder.inheritIO().start().waitFor();
    }
    return animated(
        builder,
        title,
        results,
        results && terminal ? null : out,
        err,
        comparison,
        width(environment));
  }

  static int animated(
      ProcessBuilder builder, String title, boolean results, PrintStream out, PrintStream err)
      throws Exception {
    return animated(builder, title, results, out, err, null);
  }

  static int animated(
      ProcessBuilder builder,
      String title,
      boolean results,
      PrintStream out,
      PrintStream err,
      BenchmarkComparison comparison)
      throws Exception {
    return animated(builder, title, results, out, err, comparison, 80);
  }

  static int animated(
      ProcessBuilder builder,
      String title,
      boolean results,
      PrintStream out,
      PrintStream err,
      BenchmarkComparison comparison,
      int width)
      throws Exception {
    Path stdout =
        Files.createTempFile(
            Files.createDirectories(Path.of("build/smh-reports")), "run-", ".json");
    Path stderr = Files.createTempFile("smh-stderr-", ".log");
    Path stream = Files.createTempDirectory(stdout.getParent(), "cases-");
    boolean complete = false;
    builder.environment().put("SMH_RESULT_STREAM", stream.toAbsolutePath().toString());
    Process child = null;
    Thread shutdown = null;
    long start = System.nanoTime();
    boolean liveView = results && out == null && comparison == null;
    LiveReport live = new LiveReport(err, width, liveView);
    try {
      child =
          builder
              .redirectInput(ProcessBuilder.Redirect.INHERIT)
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile())
              .start();
      Process running = child;
      shutdown = new Thread(() -> restore(running, err), "smh-terminal-cleanup");
      Runtime.getRuntime().addShutdownHook(shutdown);
      err.print("\033[?25l");
      int frame = 0, shown = 0;
      do {
        shown =
            liveView
                ? drainLive(stream, shown, live)
                : drain(stream, shown, err, comparison, width);
        if (!liveView || shown == 0)
          err.print(frame(frame++, (System.nanoTime() - start) / 1e9, title, width));
        err.flush();
      } while (!child.waitFor(80, TimeUnit.MILLISECONDS));
      shown =
          liveView ? drainLive(stream, shown, live) : drain(stream, shown, err, comparison, width);
      live.close();
      int status = child.exitValue();
      complete = status == 0;
      err.print("\r\033[2K");
      err.printf(
          Locale.ROOT,
          "  %s%s%s  %s  %.2f s%n",
          status == 0 ? GREEN : ROSE,
          status == 0 ? "✓ Complete" : "✕ Failed (exit " + status + ")",
          RESET,
          clean(title),
          (System.nanoTime() - start) / 1e9);
      // Interactive results stay readable; preserve the complete raw report on disk.
      // Redirected stdout remains byte-for-byte machine output, including failed children.
      if (out != null) Files.copy(stdout, out);
      Files.copy(stderr, err);
      if (results && status == 0) {
        if (shown == 0 || liveView) summary(Files.readString(stdout), err, comparison, width);
        else footer(Files.readString(stdout), err, comparison);
      }
      if (out == null) err.println("  Raw report: " + path(stdout));
      return status;
    } finally {
      live.close();
      if (!complete) err.println("  Failed-run files: " + path(stdout) + " · " + path(stderr));
      restore(child, err);
      if (shutdown != null) Runtime.getRuntime().removeShutdownHook(shutdown);
      if (complete) {
        if (out != null) Files.deleteIfExists(stdout);
        Files.deleteIfExists(stderr);
      }
      finishStream(stream, complete, err);
    }
  }

  static void finishStream(Path stream, boolean complete, PrintStream err) throws IOException {
    if (!complete && Files.isRegularFile(stream.resolve("0.json"))) {
      err.println("  Completed cases before failure: " + path(stream));
      return;
    }
    try (var files = Files.list(stream)) {
      for (Path file : files.toList()) Files.delete(file);
    }
    Files.delete(stream);
  }

  static String path(Path file) {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    Path absolute = file.toAbsolutePath().normalize();
    return cwd.getRoot().equals(absolute.getRoot())
        ? cwd.relativize(absolute).toString()
        : absolute.toString();
  }

  static int drain(
      Path directory, int shown, PrintStream err, BenchmarkComparison comparison, int width)
      throws IOException {
    while (Files.isRegularFile(directory.resolve(shown + ".json"))) {
      String json = Files.readString(directory.resolve(shown + ".json"));
      err.print("\r\033[2K");
      cards("[" + json + "]", err, comparison, width, shown == 0);
      err.flush();
      shown++;
    }
    return shown;
  }

  static int drainLive(Path directory, int shown, LiveReport live) throws IOException {
    int available = shown;
    while (Files.isRegularFile(directory.resolve(available + ".json"))) available++;
    if (available != shown) {
      JsonArray snapshot = new JsonArray();
      for (int i = 0; i < available; i++)
        snapshot.add(JsonParser.parseString(Files.readString(directory.resolve(i + ".json"))));
      live.update(snapshot, "Benchmarking · " + available + " cases complete");
    }
    return available;
  }

  static void terminate(Process child) {
    if (child == null || !child.isAlive()) return;
    // Snapshot the tree first: a destroyed parent can no longer enumerate its descendants.
    List<ProcessHandle> tree = new ArrayList<>(child.descendants().toList());
    tree.add(child.toHandle());
    for (ProcessHandle process : tree) process.destroyForcibly();
    // join() never throws on interruption, so cleanup also completes from interrupted threads.
    java.util.concurrent.CompletableFuture.allOf(
            tree.stream()
                .map(ProcessHandle::onExit)
                .toArray(java.util.concurrent.CompletableFuture[]::new))
        .completeOnTimeout(null, 10, TimeUnit.SECONDS)
        .join();
    // Windows can report a terminated process as alive for a moment after its exit future
    // completes; re-check and re-issue termination for anything that survived.
    for (ProcessHandle process : tree) {
      if (!process.isAlive()) continue;
      process.destroyForcibly();
      process.onExit().completeOnTimeout(null, 2, TimeUnit.SECONDS).join();
    }
    child.onExit().completeOnTimeout(null, 2, TimeUnit.SECONDS).join();
  }

  static void restore(Process child, PrintStream err) {
    terminate(child);
    err.print("\033[0m\033[?25h");
    err.flush();
  }

  static String rgb(int red, int green, int blue) {
    return "\033[38;2;" + red + ";" + green + ";" + blue + "m";
  }

  static String gradient(String text, int offset) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      double wave = (Math.sin((i + offset) * Math.PI / 20) + 1) / 2;
      result
          .append(rgb((int) (105 + 76 * wave), (int) (222 - 65 * wave), 245))
          .append(text.charAt(i));
    }
    return result.append(RESET).toString();
  }

  static String frame(int tick, double seconds) {
    return frame(tick, seconds, "Running");
  }

  static String frame(int tick, double seconds, String title) {
    return frame(tick, seconds, title, 80);
  }

  static String frame(int tick, double seconds, String title, int width) {
    int titleWidth = Math.min(40, width - 20);
    return String.format(
        Locale.ROOT,
        "\r\033[2K  %s%c%s  %-" + titleWidth + "." + titleWidth + "s  %s%.1f s%s",
        CYAN,
        FRAMES.charAt(Math.floorMod(tick, FRAMES.length())),
        RESET,
        clean(title),
        DIM,
        seconds,
        RESET);
  }

  static final ThreadLocal<PrintStream> BUILD_LOG = new ThreadLocal<>();

  static final ThreadLocal<BuildScope> BUILD_SCOPE = new ThreadLocal<>();

  static final class BuildScope {
    volatile Runnable cleanup = () -> {};

    void finishBuild() {
      cleanup.run();
    }
  }

  static final class BuildCleanup implements Runnable {
    private final BuildLog output;
    private final BuildLog command;
    private Process child;
    private boolean complete;

    BuildCleanup(BuildLog output, BuildLog command) {
      this.output = output;
      this.command = command;
    }

    synchronized Process start(ProcessBuilder builder) throws IOException {
      if (complete) throw new IOException("Build interrupted before process launch");
      child = builder.start();
      child.getOutputStream().close();
      return child;
    }

    @Override
    public synchronized void run() {
      if (complete) return;
      terminate(child);
      output.publish();
      command.publish();
      complete = true;
    }
  }

  static PrintStream buildMessages() {
    return Optional.ofNullable(BUILD_LOG.get()).orElse(System.err);
  }

  static int build(ProcessBuilder builder, String title, Path directory, boolean verbose)
      throws Exception {
    return build(
        builder,
        title,
        directory,
        verbose,
        BUILD_LOG.get() == null
            && enabled(
                Mode.auto,
                Optional.ofNullable(System.console()).map(Console::isTerminal).orElse(false),
                System.getenv()),
        buildMessages());
  }

  static int build(
      ProcessBuilder builder,
      String title,
      Path directory,
      boolean verbose,
      boolean animate,
      PrintStream err)
      throws Exception {
    Files.createDirectories(directory);
    Path staging = Files.createTempFile("compiler-", ".log");
    Path log = directory.resolve(staging.getFileName());
    Path stagedCommand =
        Files.writeString(Path.of(staging + ".command.json"), new Gson().toJson(builder.command()));
    BuildLog output = new BuildLog(staging, log, System.err);
    BuildCleanup cleanup =
        new BuildCleanup(
            output, new BuildLog(stagedCommand, Path.of(log + ".command.json"), System.err));
    BuildScope scope = BUILD_SCOPE.get();
    if (scope != null) scope.cleanup = cleanup;
    Thread shutdown = new Thread(cleanup, "smh-build-cleanup");
    Runtime.getRuntime().addShutdownHook(shutdown);
    long start = System.nanoTime();
    int status;
    try {
      Process child =
          cleanup.start(builder.redirectErrorStream(true).redirectOutput(staging.toFile()));
      int tick = 0;
      while (!child.waitFor(80, TimeUnit.MILLISECONDS)) {
        if (animate) {
          err.print(frame(tick++, (System.nanoTime() - start) / 1e9, title));
          err.flush();
        }
      }
      status = child.exitValue();
    } finally {
      cleanup.run();
      if (scope != null) scope.cleanup = () -> {};
      BuildLog.removeHook(shutdown);
    }
    log = output.publish();
    if (animate) err.print("\r\033[2K");
    err.printf(
        Locale.ROOT,
        "  %s · %s · %.2f s · log: %s%n",
        title,
        status == 0 ? "complete" : "failed",
        (System.nanoTime() - start) / 1e9,
        path(log));
    if (verbose) Files.copy(log, err);
    return status;
  }

  static String clean(String value) {
    return value.replaceAll("[\\p{Cc}\\p{Cf}]", "?");
  }

  static void summary(String json, PrintStream err) {
    summary(json, err, null);
  }

  static void summary(String json, PrintStream err, BenchmarkComparison comparison) {
    summary(json, err, comparison, 80);
  }

  static void summary(String json, PrintStream err, BenchmarkComparison comparison, int width) {
    cards(json, err, comparison, width, true);
    footer(json, err, comparison);
  }

  static void cards(String json, PrintStream err, BenchmarkComparison comparison) {
    cards(json, err, comparison, 80, true);
  }

  static void cards(
      String json, PrintStream err, BenchmarkComparison comparison, int width, boolean heading) {
    // Diagnostics and malformed reports never turn a successful child into a failed run.
    // The original stdout is always preserved independently of presentation.
    try {
      JsonArray rows = JsonParser.parseString(json).getAsJsonArray();
      StringBuilder table = new StringBuilder();
      if (comparison != null && heading && !rows.isEmpty()) table.append(comparisonHeading(width));
      for (List<JsonObject> group : VariantCharts.groups(rows)) {
        // Shared axes suit same-workload launch comparisons; parameterized steady-state cases
        // are different workloads and each gets its own card.
        if (comparison == null && group.size() > 1 && VariantCharts.overlay(group)) {
          table.append(VariantCharts.render(group, width));
          continue;
        }
        for (JsonObject row : group) {
          JsonObject metric = row.getAsJsonObject("primaryMetric");
          if (comparison != null) {
            table.append(comparisonRow(row, comparison, width));
            if (row.has("smh")
                && row.getAsJsonObject("smh").has("experimentMode")
                && !row.getAsJsonObject("smh")
                    .get("experimentMode")
                    .getAsString()
                    .equals("steady")) {
              List<Double> observations = new ArrayList<>();
              for (JsonElement fork : metric.getAsJsonArray("rawData"))
                for (JsonElement value : fork.getAsJsonArray())
                  observations.add(value.getAsDouble());
              table.append(ResultDetails.render(row, observations, width));
            } else table.append(ResultDetails.conditions(row));
            continue;
          }
          boolean steady =
              !row.has("smh")
                  || !row.getAsJsonObject("smh").has("experimentMode")
                  || row.getAsJsonObject("smh")
                      .get("experimentMode")
                      .getAsString()
                      .equals("steady");
          table
              .append("\n  ")
              .append(TEXT)
              .append(label(row, width - 4))
              .append(RESET)
              .append("\n  ")
              .append(EDGE)
              .append("─".repeat(width - 4))
              .append(RESET)
              .append("\n\n");
          // Steady-state cards lead with the median headline; experiment cards keep the mean.
          if (!steady)
            table
                .append("    ")
                .append(BOLD)
                .append(CYAN)
                .append(ResultDetails.number(metric.get("score").getAsDouble()))
                .append(' ')
                .append(ResultDetails.unit(metric.get("scoreUnit").getAsString()))
                .append(RESET)
                .append(DIM)
                .append("  mean")
                .append(RESET)
                .append("\n\n");
          List<Double> samples = new ArrayList<>();
          for (JsonElement fork : metric.getAsJsonArray("rawData"))
            for (JsonElement value : fork.getAsJsonArray()) samples.add(value.getAsDouble());
          if (metric.getAsJsonArray("rawData").size() > 1)
            table
                .append(DIM)
                .append("    ")
                .append(metric.getAsJsonArray("rawData").size())
                .append(" forks · iteration scores shown in fork order")
                .append(RESET)
                .append('\n');
          if (!samples.isEmpty()) table.append(ResultDetails.render(row, samples, width));
          else table.append(DIM).append("    No iteration scores").append(RESET).append('\n');
          if (row.has("measurementBatchSize") && row.get("measurementBatchSize").getAsLong() > 1)
            table
                .append(DIM)
                .append("    Batch ")
                .append(clean(row.get("measurementBatchSize").getAsString()))
                .append(" invocations")
                .append(RESET)
                .append('\n');
        }
      }
      err.print(table);
    } catch (RuntimeException ignored) {
      err.println("  No wall-time summary available; see raw output.");
    }
  }

  static String label(JsonObject row, int limit) {
    String name = clean(row.get("benchmark").getAsString());
    int packageEnd = name.lastIndexOf('.', name.lastIndexOf('.') - 1);
    if (packageEnd >= 0) name = name.substring(packageEnd + 1);
    if ("ss".equals(row.get("mode").getAsString())) name += " [ss]";
    String params =
        (row.has("params") ? row.getAsJsonObject("params") : new JsonObject())
            .entrySet().stream()
                .map(e -> clean(e.getKey() + "=" + e.getValue().getAsString()))
                .collect(java.util.stream.Collectors.joining(" · "));
    if (params.isEmpty()) return fit(name, limit);
    if (name.length() + params.length() + 3 <= limit) return name + " · " + params;
    if (limit < 12) return fit(name + " · " + params, limit);
    int nameWidth = Math.min(name.length(), Math.max(8, limit * 2 / 3));
    return fit(name, nameWidth) + " · " + fit(params, limit - nameWidth - 3);
  }

  static String fit(String value, int limit) {
    if (limit <= 1) return value.isEmpty() ? "" : "…";
    return value.length() <= limit ? value : "…" + value.substring(value.length() - limit + 1);
  }

  static String comparisonHeading(int width) {
    String columns =
        width < 72
            ? "Change scale −20% ───── 0 ───── +20%"
            : String.format(
                Locale.ROOT,
                "%-" + Math.min(44, width - 58) + "s  %13s  %s  %7s  %6s",
                "Benchmark",
                "Median",
                "−20%      0      +20%",
                "Change",
                "Spread");
    return "\n  "
        + TEXT
        + "Median · observed change vs baseline"
        + RESET
        + "\n  "
        + DIM
        + columns
        + RESET
        + "\n  "
        + EDGE
        + "─".repeat(width - 4)
        + RESET
        + "\n";
  }

  static String comparisonRow(JsonObject row, BenchmarkComparison comparison, int width) {
    JsonObject metric = row.getAsJsonObject("primaryMetric");
    BenchmarkComparison.Stats stats = BenchmarkComparison.stats(row);
    String score =
        ResultDetails.number(stats.median())
            + " "
            + ResultDetails.unit(metric.get("scoreUnit").getAsString());
    OptionalDouble change = comparison.change(row);
    OptionalDouble spread = comparison.spread(row);
    boolean within = comparison.withinSpread(row);
    String plot =
        change.isPresent()
            ? changeBar(change.getAsDouble())
            : DIM + "       no delta      " + RESET;
    String delta = change.isPresent() ? percent(change.getAsDouble()) : "—";
    String band =
        spread.isPresent()
            ? (within ? "≈ " : "± ") + percent(spread.getAsDouble()).replace("+", "")
            : "";
    String color = deltaColor(change, row.get("mode").getAsString(), within);
    String name = label(row, width < 72 ? width - 4 : Math.min(44, width - 58));
    if (width < 72)
      return "  "
          + TEXT
          + fit(name, width - 4)
          + RESET
          + "\n    "
          + CYAN
          + score
          + RESET
          + "\n    "
          + plot
          + "  "
          + color
          + delta
          + RESET
          + "  "
          + DIM
          + band
          + RESET
          + "\n";
    int labelWidth = Math.min(44, width - 58);
    return String.format(
        Locale.ROOT,
        "  %s%-" + labelWidth + "s%s  %s%13s%s  %s  %s%7s%s  %s%6s%s%n",
        TEXT,
        fit(name, labelWidth),
        RESET,
        CYAN,
        score,
        RESET,
        plot,
        color,
        delta,
        RESET,
        DIM,
        band,
        RESET);
  }

  /** Shared ±20% scale for streamed comparisons; arrows mark values outside the scale. */
  static String changeBar(double percent) {
    int cells = (int) Math.round(Math.min(20, Math.abs(percent)) / 2);
    if (percent != 0 && cells == 0) cells = 1;
    String bar = "━".repeat(cells);
    if (Math.abs(percent) > 20) bar = percent < 0 ? "◀" + bar.substring(1) : bar.substring(1) + "▶";
    return percent < 0
        ? " ".repeat(10 - cells) + CYAN + bar + DIM + "│" + " ".repeat(10) + RESET
        : " ".repeat(10) + DIM + "│" + VIOLET + bar + " ".repeat(10 - cells) + RESET;
  }

  static String percent(double value) {
    if (value == 0) return "0%";
    if (Math.abs(value) < 1) return value < 0 ? "−<1%" : "+<1%";
    return (value < 0 ? "−" : "+") + ResultDetails.number(Math.rint(Math.abs(value))) + "%";
  }

  static void footer(String json, PrintStream err, BenchmarkComparison comparison) {
    try {
      var environments = new LinkedHashSet<String>();
      var qualifications = new LinkedHashSet<String>();
      var modes = new LinkedHashSet<String>();
      for (var value : JsonParser.parseString(json).getAsJsonArray()) {
        var row = value.getAsJsonObject();
        var smh = row.getAsJsonObject("smh");
        var execution = smh == null ? null : smh.getAsJsonObject("execution");
        String engine =
            execution == null ? "" : clean(execution.get("backend").getAsString()) + " · ";
        if (execution != null
            && execution.has("purpose")
            && !execution.get("purpose").getAsString().equals("measurement"))
          qualifications.add(clean(execution.get("purpose").getAsString()) + " run");
        if (execution != null && new JsonPrimitive("external").equals(execution.get("provenance")))
          qualifications.add("external binary · build provenance unverified");
        if (execution != null && new JsonPrimitive("jmh").equals(execution.get("harness"))) {
          engine = "JMH " + JmhBuilder.VERSION + " · " + engine;
          if (execution.has("aotCacheKey")) engine += "Leyden · ";
          qualifications.add("JMH harness · cross-harness equivalence not established");
        }
        if (row.has("vmName"))
          environments.add(
              engine
                  + clean(row.get("vmName").getAsString())
                  + " · JDK "
                  + clean(row.get("jdkVersion").getAsString()));
        if (row.has("mode")) modes.add(row.get("mode").getAsString());
      }
      if (comparison != null) {
        err.print(summaryBlock(JsonParser.parseString(json).getAsJsonArray(), comparison));
        err.println("  " + DIM + "Baseline · " + clean(comparison.description()) + RESET);
        String quantity =
            modes.equals(Set.of("thrpt"))
                ? "throughput"
                : modes.contains("thrpt") || modes.contains("startup")
                    ? "of each row's metric"
                    : "time";
        err.println(
            "\n  "
                + DIM
                + "− less "
                + quantity
                + " · + more "
                + quantity
                + " · ≈ change within spread · arrows exceed ±20%"
                + RESET);
        err.println(
            "  "
                + DIM
                + "Spread · both runs' median absolute deviations relative to their medians"
                + RESET);
        err.println("  " + DIM + "Significance not assessed · host/build/batch unchecked" + RESET);
      }
      for (String environment : environments) err.println("  " + DIM + environment + RESET);
      if (modes.contains("ss")) err.println("  " + DIM + "ss · single-shot time" + RESET);
      for (String qualification : qualifications) err.println("  " + DIM + qualification + RESET);
    } catch (RuntimeException ignored) {
      // Non-report stdout is retained unchanged.
    }
  }

  static String deltaColor(OptionalDouble change, String mode) {
    return deltaColor(change, mode, false);
  }

  /** Green is faster and rose is slower for the row's score direction; dim is no evidence. */
  static String deltaColor(OptionalDouble change, String mode, boolean withinSpread) {
    if (change.isEmpty() || change.getAsDouble() == 0 || withinSpread) return DIM;
    boolean faster = "thrpt".equals(mode) == change.getAsDouble() > 0;
    return faster ? GREEN : ROSE;
  }

  /** One verdict per case, hyperfine style: a speed factor, the medians, and the spread band. */
  static String summaryBlock(JsonArray rows, BenchmarkComparison comparison) {
    StringBuilder out = new StringBuilder("  " + TEXT + "Summary · vs baseline" + RESET + "\n");
    int labelWidth = 0;
    List<String> labels = new ArrayList<>();
    for (JsonElement element : rows) {
      String name = label(element.getAsJsonObject(), 44);
      labels.add(name);
      labelWidth = Math.max(labelWidth, name.length());
    }
    for (int i = 0; i < rows.size(); i++) {
      JsonObject row = rows.get(i).getAsJsonObject();
      OptionalDouble previous = comparison.previous(row);
      String unit =
          ResultDetails.unit(row.getAsJsonObject("primaryMetric").get("scoreUnit").getAsString());
      out.append("    ")
          .append(TEXT)
          .append(String.format(Locale.ROOT, "%-" + labelWidth + "s", labels.get(i)))
          .append(RESET)
          .append("  ");
      double current = BenchmarkComparison.stats(row).median();
      if (previous.isEmpty() || previous.getAsDouble() == 0 || current == 0) {
        out.append(DIM).append("no baseline match").append(RESET).append('\n');
        continue;
      }
      boolean higher = BenchmarkComparison.higherIsBetter(row);
      double ratio = higher ? current / previous.getAsDouble() : previous.getAsDouble() / current;
      boolean within = comparison.withinSpread(row);
      String verdict;
      if (!Double.isFinite(ratio)) verdict = "—";
      else if (within) verdict = "about the same";
      else if (ratio >= 1) verdict = ResultDetails.number(ratio) + "× faster";
      else verdict = ResultDetails.number(1 / ratio) + "× slower";
      String color = within || !Double.isFinite(ratio) ? DIM : ratio >= 1 ? GREEN : ROSE;
      out.append(color)
          .append(String.format(Locale.ROOT, "%-15s", verdict))
          .append(RESET)
          .append(DIM)
          .append(ResultDetails.number(current))
          .append(" vs ")
          .append(ResultDetails.number(previous.getAsDouble()))
          .append(' ')
          .append(unit);
      OptionalDouble spread = comparison.spread(row);
      if (spread.isPresent())
        out.append(" · ")
            .append(within ? "within " : "")
            .append("spread ±")
            .append(ResultDetails.number(Math.rint(spread.getAsDouble())))
            .append('%');
      out.append(RESET).append('\n');
    }
    return out.append('\n').toString();
  }

  static String sparkline(List<Double> samples) {
    if (samples.isEmpty()) return "—";
    double min = Collections.min(samples), max = Collections.max(samples);
    StringBuilder result = new StringBuilder();
    // Bounded display; raw JSON always retains every observation.
    for (int i = 0; i < Math.min(40, samples.size()); i++) {
      double value = samples.get(i * samples.size() / Math.min(40, samples.size()));
      int level = max == min ? 3 : (int) Math.round((value - min) / (max - min) * 7);
      result.append("▁▂▃▄▅▆▇█".charAt(Math.clamp(level, 0, 7)));
    }
    return result.toString();
  }
}
