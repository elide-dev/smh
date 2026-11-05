// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("portable")
class CliTest {
  @TempDir Path directory;

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", Platform.current().executable("java"))
        .toString();
  }

  private record Output(int status, String stdout, String stderr) {}

  @Test
  void terminalLoggingKeepsWarningsButSuppressesRoutineInformation() {
    try (var logging = Main.logging()) {
      assertFalse(
          logging.router().route("dev.elide.smh.cli", System.Logger.Level.INFO).isEnabled());
      assertTrue(
          logging.router().route("dev.elide.smh.cli", System.Logger.Level.WARNING).isEnabled());
      assertTrue(
          logging.router().route("dev.elide.smh.cli", System.Logger.Level.ERROR).isEnabled());
    }
  }

  private Output invoke(String... args) {
    PrintStream out = System.out, err = System.err;
    ByteArrayOutputStream stdout = new ByteArrayOutputStream(),
        stderr = new ByteArrayOutputStream();
    try (PrintStream captureOut = new PrintStream(stdout);
        PrintStream captureErr = new PrintStream(stderr)) {
      System.setOut(captureOut);
      System.setErr(captureErr);
      int status = Main.execute(args);
      return new Output(status, stdout.toString(), stderr.toString());
    } finally {
      System.setOut(out);
      System.setErr(err);
    }
  }

  @Test
  void realMicronautCreatesRootAndSubcommandsAndPreservesExitCodes() {
    Output root = invoke();
    assertEquals(0, root.status);
    assertTrue(root.stdout.startsWith("Usage: smh"), root.stdout);
    assertTrue(root.stdout.contains("build"));
    assertEquals(0, invoke("--help").status);
    Output version = invoke("--version");
    assertEquals(0, version.status);
    assertEquals("smh 0.2.0", version.stdout.strip()); // x-release-please-version
    assertEquals(0, invoke("build", "--help").status);
    assertEquals(0, invoke("simulate", "--help").status);
    assertEquals(2, invoke("simulate").status);
    Output missing = invoke("build");
    assertEquals(2, missing.status);
    assertTrue(missing.stderr.contains("--config"), missing.stderr);
    assertEquals(2, invoke("unrecognized-command").status);
  }

  @Test
  void buildOptionsOverrideJsonThroughThePublicParser() throws Exception {
    Path home = Files.createDirectories(directory.resolve("distribution"));
    Files.writeString(home.resolve("smh.jar"), "processor marker");
    Files.writeString(home.resolve("smh-runtime.jar"), "runtime marker");
    Files.createDirectories(home.resolve("lib"));
    Path source = Files.writeString(directory.resolve("Work.java"), "public class Work {}");
    JsonObject config = new JsonObject();
    config.addProperty("home", home.toString());
    config.addProperty("output", directory.resolve("original").toString());
    com.google.gson.JsonArray sources = new com.google.gson.JsonArray();
    sources.add(source.toString());
    config.add("sources", sources);
    config.addProperty("nativeImage", java());
    Path file = Files.writeString(directory.resolve("build.json"), config.toString());
    Output defaults = invoke("build", "--config", file.toString(), "--dry-run");
    assertEquals(0, defaults.status, defaults.stderr);
    assertEquals(
        TerminalDisplay.path(
            directory.resolve("original").resolve(Platform.current().executable("benchmarks"))),
        defaults.stdout.strip());
    Path profile = Files.writeString(directory.resolve("training.iprof"), "profile marker");
    Path output = directory.resolve("override");
    Output overridden =
        invoke(
            "build",
            "--config",
            file.toString(),
            "--home",
            home.toString(),
            "--java-home",
            System.getProperty("java.home"),
            "-O3",
            "--pgo",
            "use",
            "--profile",
            profile.toString(),
            "--output",
            output.toString(),
            "--no-layers",
            "--shared",
            "--dry-run");
    assertEquals(0, overridden.status, overridden.stderr);
    assertEquals(
        TerminalDisplay.path(output.resolve(Platform.current().sharedLibrary("benchmarks"))),
        overridden.stdout.strip());
    assertTrue(overridden.stderr.contains("-O3"));
    assertTrue(overridden.stderr.contains(Platform.current().executable("javac")));
    assertTrue(overridden.stderr.contains("--pgo=" + profile));
    assertFalse(overridden.stderr.contains("-H:LayerUse"));
    Output base = invoke("build", "--config", file.toString(), "--base-only", "--dry-run");
    assertEquals(0, base.status, base.stderr);
    assertFalse(base.stderr.contains("--release"));
    assertTrue(base.stdout.strip().endsWith("smh-base.nil"));
    assertNotEquals(
        0,
        invoke("build", "--config", file.toString(), "--base-only", "--no-layers", "--dry-run")
            .status);
    assertNotEquals(
        0,
        invoke("build", "--config", file.toString(), "--base-only", "--bytecode-only", "--dry-run")
            .status);
    assertEquals(
        0, invoke("build", "--config", file.toString(), "--bytecode-only", "--dry-run").status);
  }

  @Test
  void runUsesFreshProcessesForwardsConfigurationAndReturnsTheChildStatus() throws Exception {
    Path source =
        Files.writeString(
            directory.resolve("Exit.java"),
            """
            class Exit {
              public static void main(String[] args) throws Exception {
                if (args.length != 0) java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "forwarded");
                System.exit(7);
              }
            }
            """);
    Main.Run run = new Main.Run();
    run.binary = Path.of(java());
    run.config = source;
    assertEquals(7, run.call());
    run.bootstrap = Path.of(java());
    run.binary = source;
    run.config = directory.resolve("child-output");
    assertEquals(7, run.call());
    assertEquals("forwarded", Files.readString(run.config));
    run.profileOutput = directory.resolve("profile.iprof");
    assertThrows(IllegalArgumentException.class, run::call);
    run.bootstrap = null;
    run.compare =
        Files.writeString(
            directory.resolve("baseline.json"),
            BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(100)).toString());
    run.display = TerminalDisplay.Mode.plain;
    assertThrows(IllegalArgumentException.class, run::call);
    run.compare = null;
    run.binary = Path.of(java());
    run.config = null;
    assertNotEquals(
        0, run.call(), "HotSpot rejects the SVM profile option; failure must propagate");
    assertEquals(0, new CommandLine(new Main.Run()).execute("--help"));
  }

  @Test
  void benchBuildsOnceButExecutesEveryTimeAndSupportsOverrides() throws Exception {
    Path home = Files.createDirectories(directory.resolve("sdk"));
    Files.writeString(home.resolve("smh.jar"), "processor");
    Files.writeString(home.resolve("smh-runtime.jar"), "runtime");
    Files.createDirectory(home.resolve("lib"));
    boolean windows = Platform.current().os() == Platform.OperatingSystem.WINDOWS;
    Path tools = Files.createDirectories(directory.resolve("tools/bin"));
    Path compiler = Files.writeString(tools.resolve("javac"), "#!/bin/sh\necho compiler-v1\n");
    assertTrue(compiler.toFile().setExecutable(true));
    Path source = Files.writeString(directory.resolve("Work.java"), "source");
    JsonObject build = new JsonObject();
    build.addProperty("home", home.toString());
    Path javaHome = windows ? Path.of(System.getProperty("java.home")) : tools.getParent();
    build.addProperty("javaHome", javaHome.toString());
    build.addProperty("nativeImage", windows ? java() : compiler.toString());
    build.addProperty("output", directory.resolve("out").toString());
    build.addProperty("layers", false);
    var sources = new com.google.gson.JsonArray();
    sources.add(source.toString());
    build.add("sources", sources);
    var extras = new com.google.gson.JsonArray();
    extras.add(source.toString());
    build.add("cacheInputs", extras);
    Path config = Files.writeString(directory.resolve("smh-build.json"), build.toString());
    Path run = Files.writeString(directory.resolve("smh-run.json"), "{}");
    Path executions = directory.resolve("executions");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    Main.Bench bench = new Main.Bench();
    bench.builder =
        new AotBuilder() {
          @Override
          public Path build(BuildConfig settings, boolean dryRun) throws Exception {
            calls.incrementAndGet();
            Path binary = settings.output.resolve(windows ? "benchmarks.cmd" : "benchmarks");
            Files.writeString(
                binary,
                windows
                    ? "@echo off\r\necho run >> \"" + executions + "\"\r\necho []\r\n"
                    : "#!/bin/sh\necho run >> '" + executions + "'\necho '[]'\n");
            assertTrue(binary.toFile().setExecutable(true));
            return binary;
          }
        };
    var parser = new CommandLine(bench);
    parser.parseArgs(
        "--build-config", config.toString(), "--config", run.toString(), "--display", "plain");
    assertEquals(0, bench.call());
    int compiled = calls.get();
    assertEquals(0, bench.call());
    assertEquals(compiled, calls.get());
    assertEquals(2, Files.readAllLines(executions).size());
    Path baseline =
        Files.writeString(
            directory.resolve("baseline.json"),
            BenchmarkComparisonTest.rows(BenchmarkComparisonTest.row(1)).toString());
    parser.parseArgs(
        "--build-config",
        config.toString(),
        "--config",
        run.toString(),
        "--home",
        home.toString(),
        "--java-home",
        javaHome.toString(),
        "-O3",
        "--verbose",
        "--rebuild",
        "--display",
        "tui",
        "--compare",
        baseline.toString());
    assertEquals(0, bench.call());
    assertTrue(calls.get() > compiled);
    assertEquals(3, Files.readAllLines(executions).size());
    bench.simulationRunner =
        new CallgrindRunner(
            (command, stdout, stderr) -> {
              if (command.contains("--version")) Files.writeString(stdout, "valgrind-3.25.1");
              else {
                Path destination = stdout.getParent();
                Files.writeString(
                    destination.resolve("execution.json"),
                    "[{\"benchmark\":\"work\",\"params\":{},\"backend\":\"callgrind\",\"status\":\"pending-counter-import\",\"measurementIterations\":1,\"operationsPerIteration\":3}]");
                Files.writeString(
                    destination.resolve("callgrind.out.17.1"),
                    StartupExperimentTest.dump(1, "--dump-after=" + CallgrindRunner.REGION, "100")
                        + "thread: 1\n");
                Files.writeString(
                    destination.resolve("callgrind.out.17"),
                    StartupExperimentTest.dump(2, "Program termination", "0") + "thread: 1\n");
              }
              return 0;
            },
            Platform.parse("Linux", "amd64"));
    Files.writeString(run, "{\"backend\":\"callgrind\",\"invocationsPerIteration\":3}");
    bench.config = run;
    bench.compare = null;
    bench.display = TerminalDisplay.Mode.tui;
    bench.experiment.output = directory.resolve("simulation");
    assertEquals(0, bench.call());
    Path simBaseline = bench.experiment.output.resolve("simulation/results.json");
    bench.config = run;
    bench.compare = simBaseline;
    bench.experiment.output = directory.resolve("simulation-compare");
    assertEquals(0, bench.call());
    // Repeated compiler flags remain single entries; conflicting CPU targets fail before build.
    extras = new com.google.gson.JsonArray();
    for (String flag : java.util.List.of("-g", "-H:-DeleteLocalSymbols", "-march=compatibility"))
      extras.add(flag);
    build.add("nativeArgs", extras);
    Files.writeString(config, build.toString());
    bench.config = run;
    bench.experiment.output = directory.resolve("simulation-flags");
    assertEquals(0, bench.call());
    extras.add("-march=native");
    Files.writeString(config, build.toString());
    bench.config = run;
    bench.experiment.output = directory.resolve("simulation-conflict");
    assertThrows(IllegalArgumentException.class, bench::call);
    Files.writeString(run, "{\"mode\":\"startup\",\"backend\":\"callgrind\"}");
    assertThrows(
        IllegalArgumentException.class,
        () -> bench.simulationRunner.run(source, run, directory.resolve("rejected"), "valgrind"));
    assertEquals(0, invoke("bench", "--help").status);
    // A real compiler failure goes through the default command/logging path.
    assertNotEquals(0, invoke("build", "--config", config.toString(), "--verbose").status);
  }

  @Test
  void runtimeLoadedCliExecutesOnC2WithoutNativeImage() throws Exception {
    Path source =
        Files.writeString(
            directory.resolve("Work.java"),
            "package example; public class Work { private int n;"
                + " @org.openjdk.jmh.annotations.Benchmark public int work() { return ++n; } }");
    JsonObject build = new JsonObject();
    build.addProperty("home", Path.of("build").toAbsolutePath().toString());
    build.addProperty("nativeImage", "unavailable-native-image");
    build.addProperty("javaHome", System.getProperty("java.home"));
    com.google.gson.JsonArray sources = new com.google.gson.JsonArray();
    sources.add(source.toString());
    build.add("sources", sources);
    Path config = Files.writeString(directory.resolve("build.json"), build.toString());
    Path results = directory.resolve("results.json");
    JsonObject run = new JsonObject();
    run.addProperty("output", results.toString());
    run.addProperty("warmupIterations", 0);
    run.addProperty("measurementIterations", 1);
    run.addProperty("measurementNanos", 10000);
    Path runConfig = Files.writeString(directory.resolve("run.json"), run.toString());
    assertEquals(0, invoke("build", "--config", config.toString(), "--bytecode-only").status);
    // Change bytes to exercise the benchmark command's real javac path too.
    Files.writeString(source, Files.readString(source).replace("++n", "n += 3"));
    Output executed =
        invoke(
            "bench",
            "--build-config",
            config.toString(),
            "--config",
            runConfig.toString(),
            "--execution",
            "hotspot-c2",
            "--runtime-java-home",
            System.getProperty("java.home"),
            "--crema-host",
            directory.resolve("unused-host").toString(),
            "--display",
            "plain");
    assertEquals(0, executed.status, executed.stderr);
    assertTrue(Files.readString(results).contains("hotspot-c2"));
    Output startup =
        invoke(
            "bench",
            "--build-config",
            config.toString(),
            "--config",
            runConfig.toString(),
            "--execution",
            "hotspot-c2",
            "--mode",
            "startup",
            "--launches",
            "1",
            "--output",
            directory.resolve("startup").toString(),
            "--display",
            "tui");
    assertEquals(0, startup.status, startup.stderr);
    assertTrue(startup.stderr.contains("Launch distribution"));
    Output soak =
        invoke(
            "bench",
            "--build-config",
            config.toString(),
            "--config",
            runConfig.toString(),
            "--execution",
            "hotspot-c2",
            "--mode",
            "soak",
            "--duration",
            "1ms",
            "--output",
            directory.resolve("soak").toString(),
            "--display",
            "tui");
    assertEquals(0, soak.status, soak.stderr);
    assertTrue(soak.stderr.contains("Window distribution"));
    Output unsupported =
        invoke(
            "bench",
            "--build-config",
            config.toString(),
            "--config",
            runConfig.toString(),
            "--execution",
            "hotspot",
            "--mode",
            "startup",
            "--backend",
            "callgrind");
    assertNotEquals(0, unsupported.status);
    assertTrue(unsupported.stderr.contains("require AOT"));
    Path startupSimulation =
        Files.writeString(
            directory.resolve("startup-simulation.json"),
            "{\"mode\":\"startup\",\"backend\":\"callgrind\"}");
    Output comparedSimulation =
        invoke(
            "bench",
            "--build-config",
            config.toString(),
            "--config",
            startupSimulation.toString(),
            "--execution",
            "hotspot",
            "--compare",
            directory.resolve("startup/results.json").toString());
    assertNotEquals(0, comparedSimulation.status);
    assertTrue(comparedSimulation.stderr.contains("require AOT"), comparedSimulation.stderr);
    Path soakPolicy =
        Files.writeString(
            directory.resolve("soak-policy.json"),
            "{\"mode\":\"soak\",\"durationNanos\":1000,\"warmupIterations\":0}");
    Output budgetOnly =
        invoke(
            "bench",
            "--build-config",
            config.toString(),
            "--config",
            soakPolicy.toString(),
            "--execution",
            "hotspot",
            "--output",
            directory.resolve("budget-only").toString(),
            "--display",
            "plain");
    assertEquals(0, budgetOnly.status, budgetOnly.stderr);
    assertTrue(Files.exists(directory.resolve("budget-only/results.json")));

    assertNotEquals(
        0,
        invoke(
                "bench",
                "--build-config",
                config.toString(),
                "--config",
                runConfig.toString(),
                "--execution",
                "hotspot-c2",
                "--rebuild")
            .status);
    build.addProperty("execution", "crema");
    Files.writeString(config, build.toString());
    assertNotEquals(0, invoke("build", "--config", config.toString(), "--dry-run").status);
  }
}
