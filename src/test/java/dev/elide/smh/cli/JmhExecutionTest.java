// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.elide.smh.jmh.JmhJob;
import dev.elide.smh.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Controlled process failures and artifacts; real JVM cache evidence lives in test_jmh.py. */
@Tag("portable")
class JmhExecutionTest {
  @TempDir Path directory;

  private static JsonArray raw() {
    return JsonParser.parseString(
            """
            [{"jmhVersion":"1.37","benchmark":"test.Work.work","mode":"avgt","forks":2,
              "jdkVersion":"25","vmName":"Test VM", "primaryMetric":{"score":3,"scoreError":1,
                "scoreUnit":"ns/op","rawData":[[2,3],[3,4]]},"secondaryMetrics":{}}]
            """)
        .getAsJsonArray();
  }

  private BuildConfig config() throws Exception {
    BuildConfig c = new BuildConfig();
    c.home = Files.createDirectories(directory.resolve("sdk"));
    Files.createDirectories(c.home.resolve("lib"));
    for (String file :
        List.of(
            "smh.jar",
            "smh-runtime.jar",
            "smh-jmh.jar",
            "lib/jmh-core-1.37.jar",
            "lib/jmh-generator-annprocess-1.37.jar",
            "lib/jopt-simple-5.0.4.jar",
            "lib/commons-math3-3.6.1.jar",
            "lib/gson-2.13.2.jar"))
      Files.writeString(c.home.resolve(file), "artifact marker, not executable");
    c.javaHome = Path.of(System.getProperty("java.home"));
    c.output = directory.resolve("image");
    c.execution = "hotspot";
    c.sources.add(Files.writeString(directory.resolve("Work.java"), "benchmark source marker"));
    return c;
  }

  private void compile(List<String> command, Path cwd) throws Exception {
    assertEquals(
        "org.openjdk.jmh.generators.BenchmarkProcessor",
        command.get(command.indexOf("-processor") + 1));
    Path resource = cwd.resolve("classes/META-INF/BenchmarkList");
    Files.createDirectories(resource.getParent());
    Files.writeString(resource, "benchmark index marker");
  }

  private int execute(ProcessBuilder process, String title, Path output, boolean verbose)
      throws Exception {
    List<String> command = process.command();
    JsonObject job =
        JsonParser.parseString(Files.readString(Path.of(command.getLast()))).getAsJsonObject();
    var options = JmhJob.options(job);
    if (title.equals("Leyden training")) {
      assertEquals(0, options.getForkCount().get());
      assertTrue(options.getJvmArgsAppend().get().isEmpty());
      for (String arg : command)
        if (arg.startsWith("-XX:AOTCacheOutput="))
          Files.writeString(Path.of(arg.substring("-XX:AOTCacheOutput=".length())), "cache marker");
    } else {
      assertEquals(2, options.getForkCount().get());
      for (String arg : options.getJvmArgs().get()) {
        if (arg.startsWith("-XX:AOTCache=")) {
          String path = arg.substring("-XX:AOTCache=".length());
          Files.writeString(
              output.resolve("cache.log"),
              ("Opened AOT cache " + path + ".\ninitial full module graph: enabled\n").repeat(2));
        }
      }
    }
    Files.writeString(Path.of(options.getResult().get()), raw().toString());
    return 0;
  }

  private int run(
      BuildConfig c,
      RunConfig run,
      boolean cache,
      Path output,
      TerminalDisplay.Mode mode,
      BenchmarkComparison baseline,
      JmhExecution.Executor executor,
      boolean terminal,
      Map<String, String> env)
      throws Exception {
    return JmhExecution.run(
        c, run, 2, cache, output, mode, baseline, this::compile, executor, terminal, env);
  }

  @Test
  void jobOptionsPreserveExactJvmArgumentsAndExplicitOverrides() throws Exception {
    RunConfig run =
        RunConfig.fromJson(
            JsonParser.parseString(
                """
                {"warmupIterations":0,"measurementIterations":2,"warmupNanos":123,"measurementNanos":456,
                "modes":["AverageTime","SingleShotTime"],"params":{"label":["with spaces","quote\"value"]}}
                """
                    .replace("quote\"value", "quote\\\"value")));
    List<String> args = JmhExecution.arguments(run, 2);
    var options =
        JmhJob.options(
            JmhExecution.job(
                args, List.of("-Dlabel=a b", "-Xmx128m"), directory.resolve("raw.json"), false));
    assertEquals(List.of("-Dlabel=a b", "-Xmx128m"), new ArrayList<>(options.getJvmArgs().get()));
    assertEquals(2, options.getMeasurementIterations().get());
    assertEquals(0, options.getWarmupIterations().get());
    assertTrue(options.getJvmArgsAppend().get().isEmpty());
    assertTrue(options.getJvmArgsPrepend().get().isEmpty());
    assertTrue(args.contains("123ns"));
    assertTrue(args.contains("456ns"));
    assertEquals(2, options.getParameter("label").get().size());
    assertFalse(JmhExecution.arguments(new RunConfig(), 1).contains("-bm"));
    assertThrows(IllegalArgumentException.class, () -> JmhExecution.arguments(run, 0));
    run.params.clear();
    run.params.put("a=b", List.of("x"));
    assertThrows(IllegalArgumentException.class, () -> JmhExecution.arguments(run, 1));
    run.params.clear();
    run.params.put("a", List.of("x,y"));
    assertThrows(IllegalArgumentException.class, () -> JmhExecution.arguments(run, 1));
    for (String json :
        List.of(
            "{\"wallBatchSize\":2}",
            "{\"backend\":\"callgrind\",\"invocationsPerIteration\":1}",
            "{\"batchCalibration\":{\"targetNanos\":100,\"maximumBatchSize\":2,\"maximumAttempts\":2,\"samplesPerAttempt\":2}}")) {
      RunConfig invalid = RunConfig.fromJson(JsonParser.parseString(json));
      assertThrows(IllegalArgumentException.class, () -> JmhExecution.arguments(invalid, 1));
    }
    RunConfig invalid = new RunConfig();
    invalid.warmupStability = new StabilityPolicy(6, 10, 3, 1, .1, .1, .1);
    assertThrows(IllegalArgumentException.class, () -> JmhExecution.arguments(invalid, 1));
    invalid.warmupStability = null;
    invalid.measurementStability = new StabilityPolicy(6, 10, 3, 1, .1, .1, .1);
    assertThrows(IllegalArgumentException.class, () -> JmhExecution.arguments(invalid, 1));
  }

  @Test
  void adapterRetainsOriginalMetricsAndRejectsForeignSchemas() throws Exception {
    JsonArray raw = raw();
    JsonObject identity = new JsonObject();
    identity.addProperty("harness", "jmh");
    identity.addProperty("backend", "hotspot");
    identity.addProperty("purpose", "measurement");
    JsonArray rows = JmhExecution.adapt(raw, identity);
    assertFalse(raw.get(0).getAsJsonObject().has("smh"));
    assertEquals(
        raw.get(0).getAsJsonObject().get("primaryMetric"),
        rows.get(0).getAsJsonObject().get("primaryMetric"));
    assertEquals(
        0, new BenchmarkComparison(rows).change(rows.get(0).getAsJsonObject()).orElseThrow());
    var report = new ByteArrayOutputStream();
    TerminalDisplay.summary(rows.toString(), new PrintStream(report), null, 120);
    assertTrue(report.toString().contains("Iterations"));
    assertTrue(report.toString().contains("2 forks"));
    assertTrue(report.toString().contains("JMH 1.37"));
    raw.get(0).getAsJsonObject().add("params", new JsonObject());
    assertEquals(rows, JmhExecution.adapt(raw, identity));
    assertThrows(
        IllegalArgumentException.class, () -> JmhExecution.adapt(new JsonArray(), identity));
    raw.get(0).getAsJsonObject().addProperty("jmhVersion", "different");
    assertThrows(IllegalArgumentException.class, () -> JmhExecution.adapt(raw, identity));
    raw.get(0).getAsJsonObject().addProperty("jmhVersion", "1.37");
    raw.get(0).getAsJsonObject().addProperty("mode", "sample");
    assertThrows(IllegalArgumentException.class, () -> JmhExecution.adapt(raw, identity));
    raw.get(0).getAsJsonObject().addProperty("mode", "avgt");
    raw.get(0).getAsJsonObject().getAsJsonObject("primaryMetric").remove("rawData");
    assertThrows(IllegalArgumentException.class, () -> JmhExecution.adapt(raw, identity));
  }

  @Test
  void cachedArtifactsAreSeparateFromMeasurementAndFailuresNeverPublish() throws Exception {
    BuildConfig c = config();
    RunConfig run = new RunConfig();
    AtomicInteger trains = new AtomicInteger();
    JmhExecution.Executor executor =
        (process, title, output, verbose) -> {
          if (title.equals("Leyden training")) trains.incrementAndGet();
          return execute(process, title, output, verbose);
        };
    Path first = directory.resolve("first");
    assertEquals(
        0, run(c, run, true, first, TerminalDisplay.Mode.tui, null, executor, false, Map.of()));
    JsonObject identity =
        JsonParser.parseString(Files.readString(first.resolve("execution.json"))).getAsJsonObject();
    assertTrue(identity.has("aotCacheSha256"));
    assertEquals(1, trains.get());
    assertEquals(
        0,
        run(
            c,
            run,
            true,
            directory.resolve("second"),
            TerminalDisplay.Mode.auto,
            null,
            executor,
            true,
            Map.of()));
    assertEquals(1, trains.get());
    Path cached = Path.of(identity.get("training").getAsString()).resolve("hotspot.aot");
    Files.writeString(cached, "corrupt");
    assertEquals(
        0,
        run(
            c,
            run,
            true,
            directory.resolve("third"),
            TerminalDisplay.Mode.plain,
            null,
            executor,
            false,
            Map.of()));
    assertEquals(2, trains.get());
    c.execution = "hotspot-c2";
    c.runtimeArgs.add("-XX:+PrintCompilation");
    run.output = directory.resolve("export.json");
    c.runtimeJavaHome = c.javaHome;
    c.classpath.add(Files.createDirectory(directory.resolve("classes")));
    assertEquals(
        0,
        run(
            c,
            run,
            false,
            directory.resolve("c2"),
            TerminalDisplay.Mode.plain,
            null,
            executor,
            false,
            Map.of()));
    assertTrue(Files.readString(run.output).contains("diagnostic"));
    Path failure = directory.resolve("failed");
    assertEquals(
        17,
        run(
            c,
            run,
            false,
            failure,
            TerminalDisplay.Mode.plain,
            null,
            (p, t, d, v) -> 17,
            false,
            Map.of()));
    assertFalse(Files.exists(failure.resolve("results.json")));
    c.execution = "hotspot";
    c.classpath.clear();
    c.runtimeArgs.clear();
    run.measurementIterations = 3;
    assertThrows(
        IOException.class,
        () ->
            run(
                c,
                run,
                true,
                directory.resolve("bad-training"),
                TerminalDisplay.Mode.plain,
                null,
                (p, t, d, v) -> 1,
                false,
                Map.of()));
    assertThrows(
        IOException.class,
        () ->
            run(
                c,
                run,
                true,
                directory.resolve("missing-cache"),
                TerminalDisplay.Mode.plain,
                null,
                (p, t, d, v) -> 0,
                false,
                Map.of()));
    assertEquals(directory.resolve("image"), c.output);
  }

  @Test
  void validationRejectsUnsupportedBackendsOptionsAndIncompleteDistributions() throws Exception {
    BuildConfig c = config();
    RunConfig run = new RunConfig();
    BenchmarkComparison baseline =
        new BenchmarkComparison(JmhExecution.adapt(raw(), new JsonObject()));
    for (String backend : List.of("aot", "hotspot-c2")) {
      c.execution = backend;
      assertThrows(
          IllegalArgumentException.class,
          () ->
              run(
                  c,
                  run,
                  true,
                  directory.resolve("out"),
                  TerminalDisplay.Mode.plain,
                  null,
                  this::execute,
                  false,
                  Map.of()));
    }
    c.execution = "hotspot";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                c,
                run,
                false,
                directory.resolve("out"),
                TerminalDisplay.Mode.plain,
                baseline,
                this::execute,
                false,
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                c,
                run,
                false,
                directory.resolve("out"),
                TerminalDisplay.Mode.plain,
                null,
                this::execute,
                false,
                Map.of("_JAVA_OPTIONS", "-Xint")));
    Path home = c.javaHome;
    c.javaHome = null;
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                c,
                run,
                false,
                directory.resolve("out"),
                TerminalDisplay.Mode.plain,
                null,
                this::execute,
                false,
                Map.of()));
    c.javaHome = home;
    run.output = directory.resolve("out/jmh-results.json");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                c,
                run,
                false,
                directory.resolve("out"),
                TerminalDisplay.Mode.plain,
                null,
                this::execute,
                false,
                Map.of()));
    run.output = null;
    c.classpath.add(Files.createDirectory(directory.resolve("classes")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                c,
                run,
                true,
                directory.resolve("out"),
                TerminalDisplay.Mode.plain,
                null,
                this::execute,
                false,
                Map.of()));
    Files.delete(c.home.resolve("lib/jmh-core-1.37.jar"));
    assertThrows(IOException.class, () -> JmhBuilder.libraries(c));
  }

  @Test
  void publicCommandValidatesInputsBeforeDelegation() throws Exception {
    BuildConfig c = config();
    JsonObject build = new JsonObject();
    build.addProperty("home", c.home.toString());
    build.add("sources", new Gson().toJsonTree(List.of(c.sources.getFirst().toString())));
    Path config = Files.writeString(directory.resolve("build.json"), build.toString());
    Path run = Files.writeString(directory.resolve("run.json"), "{}");
    var command = new picocli.CommandLine(new Main.Jmh());
    command.parseArgs(
        "--build-config",
        config.toString(),
        "--config",
        run.toString(),
        "--output",
        directory.resolve("out").toString());
    assertThrows(IllegalArgumentException.class, ((Main.Jmh) command.getCommand())::call);
    Path baseline =
        Files.writeString(
            directory.resolve("baseline.json"),
            JmhExecution.adapt(raw(), new JsonObject()).toString());
    command.parseArgs(
        "--build-config",
        config.toString(),
        "--config",
        run.toString(),
        "--home",
        c.home.toString(),
        "--runtime-java-home",
        c.javaHome.toString(),
        "--compare",
        baseline.toString(),
        "--display",
        "plain",
        "--output",
        directory.resolve("out").toString());
    assertThrows(IllegalArgumentException.class, ((Main.Jmh) command.getCommand())::call);
    Path job =
        Files.writeString(
            directory.resolve("job.json"),
            JmhExecution.job(
                    List.of("no.benchmark.matches.this", "-f", "1"),
                    List.of(),
                    directory.resolve("raw.json"),
                    false)
                .toString());
    assertThrows(Exception.class, () -> JmhJob.main(new String[] {job.toString()}));
  }

  @Test
  void archiveAndCacheVerificationUseContentNotFileNames() throws Exception {
    BuildConfig c = config();
    BuildCache.Result artifact = JmhBuilder.prepare(c, this::compile);
    try (JarFile jar = new JarFile(artifact.binary().toFile())) {
      assertNotNull(jar.getEntry("META-INF/BenchmarkList"));
    }
    assertTrue(JmhBuilder.prepare(c, this::compile).hit());
    Path logs = Files.createDirectory(directory.resolve("logs")),
        cache = directory.resolve("cache.aot");
    assertThrows(IOException.class, () -> JmhExecution.verifyCache(logs, raw(), cache));
    Files.writeString(logs.resolve("run.log"), ("Opened AOT cache " + cache + ".\n").repeat(2));
    assertThrows(IOException.class, () -> JmhExecution.verifyCache(logs, raw(), cache));
    String valid =
        ("Opened AOT cache " + cache + ".\ninitial full module graph: enabled\n").repeat(2);
    Files.writeString(logs.resolve("run.log"), valid + "[error][aot] disabling cache\n");
    assertThrows(IOException.class, () -> JmhExecution.verifyCache(logs, raw(), cache));
    Files.writeString(logs.resolve("run.log"), valid);
    Files.writeString(logs.resolve("ignored.json"), "[error][aot]");
    JmhExecution.verifyCache(logs, raw(), cache);
  }
}
