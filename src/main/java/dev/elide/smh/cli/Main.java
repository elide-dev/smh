// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import io.jstach.rainbowgum.LogOutput;
import io.jstach.rainbowgum.RainbowGum;
import io.micronaut.configuration.picocli.MicronautFactory;
import jakarta.inject.*;
import java.nio.file.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;

/** Micronaut creates commands and their services using generated bean definitions. */
@Singleton
@Command(
    name = "smh",
    description = "SubstrateVM Microbenchmark Harness",
    mixinStandardHelpOptions = true,
    version = "smh 0.2.0", // x-release-please-version
    subcommands = {
      Main.Build.class,
      Main.Run.class,
      Main.Simulate.class,
      Main.Bench.class,
      Main.Jmh.class
    })
public class Main implements Callable<Integer> {
  public static void main(String[] args) {
    System.exit(execute(args));
  }

  static int execute(String[] args) {
    RainbowGum.set(Main::logging);
    try (var factory = new MicronautFactory()) {
      var command = new picocli.CommandLine(Main.class, factory);
      command.setExecutionExceptionHandler(
          (error, selected, parsed) -> CliErrors.handle(error, selected, false));
      command.setParameterExceptionHandler(
          (error, arguments) -> CliErrors.handle(error, error.getCommandLine(), true));
      return command.execute(args);
    }
  }

  static RainbowGum logging() {
    return RainbowGum.builder()
        .route(
            route ->
                route
                    .level(System.Logger.Level.WARNING)
                    .appender("stderr", appender -> appender.output(LogOutput.ofStandardErr())))
        .build();
  }

  @Option(
      names = "--error-format",
      scope = ScopeType.INHERIT,
      description = "Error output on stderr: ${COMPLETION-CANDIDATES} (default: text)")
  CliErrors.Format errorFormat = CliErrors.Format.text;

  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Mixin ExperimentOptions experiment = new ExperimentOptions();

  @Option(
      names = "--candidate",
      parameterConsumer = StartupCandidates.Arguments.class,
      description = "Command argv, ending at the next --candidate; put all SMH options first")
  java.util.List<String> candidates = new java.util.ArrayList<>();

  @Option(names = "--display", defaultValue = "auto")
  TerminalDisplay.Mode display = TerminalDisplay.Mode.auto;

  @Override
  public Integer call() throws Exception {
    if (!candidates.isEmpty()) return StartupCandidates.run(candidates, experiment, display);
    if (experiment.mode != null)
      throw new IllegalArgumentException(
          "Use --candidate commands or smh bench --mode " + experiment.mode);
    spec.commandLine().usage(System.out);
    return 0;
  }

  @Singleton
  @Command(
      name = "simulate",
      description = "Collect instruction/cache events with Callgrind on Linux",
      mixinStandardHelpOptions = true)
  public static class Simulate implements Callable<Integer> {
    @Inject CallgrindRunner runner;

    @Parameters(index = "0", description = "Native benchmark executable with debug symbols")
    Path binary;

    @Option(names = "--config", required = true)
    Path config;

    @Option(
        names = "--output",
        required = true,
        description = "New directory for raw dumps and results")
    Path output;

    @Option(names = "--valgrind", defaultValue = "valgrind")
    String valgrind;

    @Option(
        names = "--display",
        defaultValue = "auto",
        description = "Terminal display: ${COMPLETION-CANDIDATES}")
    TerminalDisplay.Mode display = TerminalDisplay.Mode.auto;

    @Option(
        names = "--compare",
        description = "Saved SMH Callgrind results.json for event comparisons")
    Path compare;

    @Override
    public Integer call() throws Exception {
      var baseline = compare == null ? null : SimulationDisplay.read(compare);
      boolean terminal =
          java.util.Optional.ofNullable(System.console())
              .map(java.io.Console::isTerminal)
              .orElse(false);
      var environment = System.getenv();
      SimulationDisplay.visual(display, terminal, environment, baseline);
      var report = runner.run(binary, config, output, valgrind);
      SimulationDisplay.publish(
          report, baseline, display, terminal, environment, System.out, System.err, output);
      return 0;
    }
  }

  @Singleton
  @Command(
      name = "build",
      description = "Compile JMH-style benchmarks into an AOT executable or shared library",
      mixinStandardHelpOptions = true)
  public static class Build implements Callable<Integer> {
    @Inject AotBuilder builder;

    @Option(names = "--config", required = true)
    Path config;

    @Option(
        names = "--home",
        description = "SMH distribution directory (smh.jar, smh-runtime.jar, lib/)")
    Path home;

    @Option(
        names = "--java-home",
        description = "JDK home for javac (overrides automatic Elide selection)")
    Path javaHome;

    @Option(
        names = {"-O", "--optimization"},
        description = "Native Image optimization: 0, 1, 2, 3, b, s")
    String optimization;

    @Option(names = "--pgo", description = "off, instrument, use")
    String pgo;

    @Option(names = "--profile")
    Path profile;

    @Option(names = "--output", description = "Output directory for this build variant")
    Path output;

    @Option(names = "--no-layers")
    boolean noLayers;

    @Option(names = "--shared", description = "Build library for the Rust bootstrapper")
    boolean shared;

    @Option(names = "--verbose", description = "Show compiler commands and full build logs")
    boolean verbose;

    @Option(names = "--dry-run", description = "Print compiler commands without executing them")
    boolean dryRun;

    @Option(
        names = "--base-only",
        description = "Prepare the reusable runtime layer without compiling benchmarks")
    boolean baseOnly;

    @Option(
        names = "--bytecode-only",
        description = "Prepare cached benchmark bytecode without native compilation")
    boolean bytecodeOnly;

    @Override
    public Integer call() throws Exception {
      BuildConfig c = BuildConfig.read(config, home);
      c.verbose = verbose;
      if (javaHome != null) c.javaHome = javaHome.toAbsolutePath();
      if (optimization != null) c.optimization = optimization;
      if (pgo != null) c.pgo = pgo;
      if (profile != null) c.profile = profile.toAbsolutePath();
      if (output != null) c.output = output.toAbsolutePath();
      if (noLayers) c.layers = false;
      if (shared) c.shared = true;
      if (bytecodeOnly) {
        if (baseOnly) throw new IllegalArgumentException("Choose --bytecode-only or --base-only");
        c.validate();
        System.out.println(
            TerminalDisplay.path(
                BytecodeBuilder.build(
                    c, dryRun, (command, cwd) -> AotBuilder.runCommand(command, cwd, c.verbose))));
        return 0;
      }
      System.out.println(
          TerminalDisplay.path(
              baseOnly ? builder.build(c, dryRun, true) : builder.build(c, dryRun)));
      return 0;
    }
  }

  @Singleton
  @Command(
      name = "bench",
      description = "Build if inputs changed, then run a fresh benchmark",
      mixinStandardHelpOptions = true)
  public static class Bench implements Callable<Integer> {
    @Inject AotBuilder builder;
    @Inject CallgrindRunner simulationRunner;

    @Mixin ExperimentOptions experiment = new ExperimentOptions();

    @Option(names = "--build-config", defaultValue = "smh-build.json")
    Path buildConfig;

    @Option(names = "--config", defaultValue = "smh-run.json")
    Path config;

    @Option(names = "--home")
    Path home;

    @Option(names = "--java-home")
    Path javaHome;

    @Option(names = "--execution", description = "aot, crema, hotspot or hotspot-c2")
    String execution;

    @Option(
        names = "--runtime-java-home",
        description = "JDK used to execute HotSpot/C2 benchmarks")
    Path runtimeJavaHome;

    @Option(
        names = "--crema-host",
        description = "Prebuilt native SMH host with Crema and Ristretto")
    Path cremaHost;

    @Option(names = {"-O", "--optimization"})
    String optimization;

    @Option(names = "--rebuild", description = "Compile a fresh artifact even on a cache hit")
    boolean rebuild;

    @Option(names = "--verbose", description = "Show full build logs")
    boolean verbose;

    @Option(names = "--display", defaultValue = "auto")
    TerminalDisplay.Mode display = TerminalDisplay.Mode.auto;

    @Option(names = "--compare")
    Path compare;

    @Override
    public Integer call() throws Exception {
      var requested =
          com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
      var effective = experiment.apply(requested);
      var run = dev.elide.smh.runtime.RunConfig.fromJson(effective);
      boolean simulation = run.backend == dev.elide.smh.runtime.MeasurementBackend.CALLGRIND;
      if (simulation) effective.remove("output");
      BenchmarkComparison comparison =
          compare == null || simulation && !run.mode.equals("startup")
              ? null
              : BenchmarkComparison.read(compare);
      var simulationBaseline =
          compare != null && simulation && !run.mode.equals("startup")
              ? SimulationDisplay.read(compare)
              : null;
      Path report = null;
      if (!effective.equals(requested)
          || !run.mode.equals("steady")
          || simulation
          || experiment.output != null) {
        report = experiment.directory();
        Files.writeString(report.resolve("requested-run.json"), requested.toString());
        if (!simulation && !run.mode.equals("startup"))
          effective.addProperty("output", report.resolve("results.json").toString());
        config = Files.writeString(report.resolve("run.json"), effective.toString());
        System.err.println("  Files · " + TerminalDisplay.path(report));
      }
      BuildConfig c = BuildConfig.read(buildConfig, home);
      c.verbose = verbose;
      if (javaHome != null) c.javaHome = javaHome.toAbsolutePath();
      if (optimization != null) c.optimization = optimization;
      if (execution != null) c.execution = execution;
      if (runtimeJavaHome != null) c.runtimeJavaHome = runtimeJavaHome.toAbsolutePath();
      if (cremaHost != null) c.cremaHost = cremaHost.toAbsolutePath();
      if (simulation) {
        if (!c.execution.equals("aot"))
          throw new IllegalArgumentException(
              "Callgrind experiment modes currently require AOT execution");
        if (c.nativeArgs.stream()
            .anyMatch(flag -> flag.startsWith("-march=") && !flag.equals("-march=compatibility")))
          throw new IllegalArgumentException("Callgrind requires -march=compatibility");
        for (String flag :
            java.util.List.of("-g", "-H:-DeleteLocalSymbols", "-march=compatibility"))
          if (!c.nativeArgs.contains(flag)) c.nativeArgs.add(flag);
      }
      ProcessBuilder process;
      Files.createDirectories(c.output);
      Path buildLog =
          report == null
              ? Files.createTempFile(c.output, "build-", ".log")
              : report.resolve("build.log");
      System.err.println("  Build log · " + TerminalDisplay.path(buildLog));
      try (var log = new java.io.PrintStream(Files.newOutputStream(buildLog))) {
        TerminalDisplay.BUILD_LOG.set(log);
        if (!c.execution.equals("aot")) {
          if (rebuild)
            throw new IllegalArgumentException("--rebuild currently applies to AOT artifacts only");
          process = RuntimeExecution.prepare(c, config);
        } else {
          TerminalDisplay.buildMessages().println("Checking build inputs…");
          BuildCache.Result artifact =
              BuildCache.build(c, rebuild, settings -> builder.build(settings, false));
          TerminalDisplay.buildMessages()
              .println(
                  (artifact.hit() ? "Cache hit · " : "Built · ") + artifact.key().substring(0, 12));
          process = RuntimeExecution.aot(artifact, config);
        }
      } finally {
        TerminalDisplay.BUILD_LOG.remove();
        if (verbose) Files.copy(buildLog, System.err);
      }
      if (run.mode.equals("startup")) {
        StartupExperiment.run(process, config, report, experiment.valgrind, display, comparison);
        return 0;
      }
      if (simulation) {
        Path output = report.resolve("simulation");
        var result =
            simulationRunner.run(
                Path.of(process.command().getFirst()), config, output, experiment.valgrind);
        SimulationDisplay.publish(
            result,
            simulationBaseline,
            display,
            java.util.Optional.ofNullable(System.console())
                .map(java.io.Console::isTerminal)
                .orElse(false),
            System.getenv(),
            System.out,
            System.err,
            output);
        return 0;
      }
      int status =
          TerminalDisplay.run(
              process, "Benchmark · " + c.execution + " · " + run.mode, display, true, comparison);
      if (status == 0 && report != null && run.output != null) {
        Files.createDirectories(run.output.toAbsolutePath().getParent());
        Files.copy(
            report.resolve("results.json"),
            run.output,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return status;
    }
  }

  @Singleton
  @Command(
      name = "jmh",
      description = "Run pinned JMH on HotSpot, optionally with a Leyden AOT cache",
      mixinStandardHelpOptions = true)
  public static class Jmh implements Callable<Integer> {
    @Option(names = "--build-config", defaultValue = "smh-build.json")
    Path buildConfig;

    @Option(names = "--config", defaultValue = "smh-run.json")
    Path config;

    @Option(names = "--home")
    Path home;

    @Option(names = "--runtime-java-home")
    Path runtimeJavaHome;

    @Option(
        names = "--execution",
        defaultValue = "hotspot",
        description = "hotspot (tiered C2) or hotspot-c2 (C2 only)")
    String execution;

    @Option(names = "--forks", defaultValue = "3")
    int forks;

    @Option(
        names = "--aot-cache",
        description = "Train/reuse a JDK 25 Leyden cache; require it in every measurement fork")
    boolean aotCache;

    @Option(
        names = "--output",
        required = true,
        description = "New directory for raw JMH, adapted results, commands and logs")
    Path output;

    @Option(names = "--display", defaultValue = "auto")
    TerminalDisplay.Mode display;

    @Option(names = "--compare")
    Path compare;

    @Option(names = "--verbose")
    boolean verbose;

    @Override
    public Integer call() throws Exception {
      var run = dev.elide.smh.runtime.RunConfig.read(config.toString());
      var baseline = compare == null ? null : BenchmarkComparison.read(compare);
      BuildConfig c = BuildConfig.read(buildConfig, home);
      c.execution = execution;
      c.verbose = verbose;
      if (runtimeJavaHome != null) c.runtimeJavaHome = runtimeJavaHome.toAbsolutePath();
      return JmhExecution.run(c, run, forks, aotCache, output, display, baseline);
    }
  }

  @Singleton
  @Command(
      name = "run",
      description = "Run a compiled native benchmark in a fresh process",
      mixinStandardHelpOptions = true)
  public static class Run implements Callable<Integer> {
    @Parameters(index = "0", description = "Native executable, or shared library with --bootstrap")
    Path binary;

    @Option(
        names = "--display",
        defaultValue = "auto",
        description = "Terminal display: ${COMPLETION-CANDIDATES}")
    TerminalDisplay.Mode display = TerminalDisplay.Mode.auto;

    @Option(
        names = "--compare",
        description = "Saved SMH wall-result JSON for terminal score deltas")
    Path compare;

    @Option(names = "--config", description = "Run configuration JSON")
    Path config;

    @Option(
        names = "--bootstrap",
        description = "Rust bootstrapper executable for a shared library")
    Path bootstrap;

    @Option(
        names = "--profile-output",
        description = "PGO instrumented executable profile destination")
    Path profileOutput;

    @Override
    public Integer call() throws Exception {
      if (bootstrap != null && profileOutput != null)
        throw new IllegalArgumentException(
            "--profile-output currently requires an executable; shared-library PGO needs"
                + " toolchain-specific profile dumping");
      BenchmarkComparison comparison = compare == null ? null : BenchmarkComparison.read(compare);
      java.util.List<String> command = new java.util.ArrayList<>();
      if (bootstrap != null) command.add(bootstrap.toAbsolutePath().toString());
      command.add(binary.toAbsolutePath().toString());
      if (profileOutput != null)
        command.add("-XX:ProfilesDumpFile=" + profileOutput.toAbsolutePath());
      if (config != null) command.add(config.toAbsolutePath().toString());
      return TerminalDisplay.run(
          new ProcessBuilder(command),
          "Benchmark · " + binary.getFileName(),
          display,
          true,
          comparison);
    }
  }
}
