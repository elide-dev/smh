// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import io.jstach.rainbowgum.LogOutput;
import io.jstach.rainbowgum.RainbowGum;
import io.micronaut.configuration.picocli.PicocliRunner;
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
    version = "smh 0.1.0", // x-release-please-version
    subcommands = {Main.Build.class, Main.Run.class, Main.Simulate.class})
public class Main implements Runnable {
  public static void main(String[] args) {
    System.exit(execute(args));
  }

  static int execute(String[] args) {
    RainbowGum.set(
        () ->
            RainbowGum.builder()
                .route(
                    route ->
                        route.appender(
                            "stderr", appender -> appender.output(LogOutput.ofStandardErr())))
                .build());
    return PicocliRunner.execute(Main.class, args);
  }

  @Spec picocli.CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(System.out);
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

    @Override
    public Integer call() throws Exception {
      System.out.println(runner.run(binary, config, output, valgrind));
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

    @Option(names = "--dry-run", description = "Print compiler commands without executing them")
    boolean dryRun;

    @Override
    public Integer call() throws Exception {
      BuildConfig c = BuildConfig.read(config, home);
      if (javaHome != null) c.javaHome = javaHome.toAbsolutePath();
      if (optimization != null) c.optimization = optimization;
      if (pgo != null) c.pgo = pgo;
      if (profile != null) c.profile = profile.toAbsolutePath();
      if (output != null) c.output = output.toAbsolutePath();
      if (noLayers) c.layers = false;
      if (shared) c.shared = true;
      System.out.println(builder.build(c, dryRun));
      return 0;
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
      java.util.List<String> command = new java.util.ArrayList<>();
      if (bootstrap != null) command.add(bootstrap.toAbsolutePath().toString());
      command.add(binary.toAbsolutePath().toString());
      if (profileOutput != null)
        command.add("-XX:ProfilesDumpFile=" + profileOutput.toAbsolutePath());
      if (config != null) command.add(config.toAbsolutePath().toString());
      return new ProcessBuilder(command).inheritIO().start().waitFor();
    }
  }
}
