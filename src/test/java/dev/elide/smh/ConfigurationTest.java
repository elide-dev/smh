// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.elide.smh.cli.BuildConfig;
import dev.elide.smh.runtime.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class ConfigurationTest {
  @TempDir Path directory;

  private Path json(String value) throws Exception {
    Path file = directory.resolve("config.json");
    Files.writeString(file, value);
    return file;
  }

  private BuildConfig validBuild() throws Exception {
    Path home = directory.resolve("distribution");
    Files.createDirectories(home);
    Files.writeString(home.resolve("smh.jar"), "fixture");
    Files.writeString(home.resolve("smh-runtime.jar"), "fixture");
    BuildConfig config = new BuildConfig();
    config.home = home;
    config.output = directory.resolve("output");
    config.sources.add(directory.resolve("src"));
    return config;
  }

  @Test
  void buildDefaultsAndExplicitOptionsResolveRelativePaths() throws Exception {
    BuildConfig defaults = BuildConfig.read(json("{\"sources\":[\"src\"]}"), null);
    assertEquals(directory.resolve("build"), defaults.home);
    assertEquals(directory.resolve("build/benchmarks"), defaults.output);
    assertEquals(directory.resolve("src"), defaults.sources.getFirst());
    JsonObject j =
        JsonParser.parseString(
                """
                {"home":"dist","output":"out","sources":["src"],"classpath":["lib.jar"],
                 "optimization":"3","pgo":"use","profile":"training.iprof","nativeImage":["python","driver.py"],
                 "layers":false,"shared":true,"observer":"example.Observer","nativeArgs":["-g"]}
                """)
            .getAsJsonObject();
    BuildConfig full = BuildConfig.read(json(j.toString()), null);
    assertEquals(directory.resolve("dist"), full.home);
    assertEquals(directory.resolve("out"), full.output);
    assertEquals(List.of(directory.resolve("lib.jar")), full.classpath);
    assertEquals("3", full.optimization);
    assertEquals("use", full.pgo);
    assertEquals(directory.resolve("training.iprof"), full.profile);
    assertEquals(List.of("python", "driver.py"), full.nativeImage);
    assertFalse(full.layers);
    assertTrue(full.shared);
    assertEquals("example.Observer", full.observer);
    assertEquals(List.of("-g"), full.nativeArgs);
    j.addProperty("nativeImage", "custom-compiler");
    BuildConfig overridden = BuildConfig.read(json(j.toString()), directory.resolve("override"));
    assertEquals(directory.resolve("override"), overridden.home);
    assertEquals(List.of("custom-compiler"), overridden.nativeImage);
    assertThrows(IllegalArgumentException.class, () -> BuildConfig.read(json("{}"), null));
    assertThrows(
        IllegalArgumentException.class, () -> BuildConfig.read(json("{\"typo\":1}"), null));
  }

  @Test
  void buildValidationCoversPgoVariantsAndPrerequisites() throws Exception {
    BuildConfig config = validBuild();
    for (String optimization : List.of("0", "1", "2", "3", "b", "s")) {
      config.optimization = optimization;
      config.validate();
    }
    config.optimization = "invalid";
    assertThrows(IllegalArgumentException.class, config::validate);
    config.optimization = "2";
    config.pgo = "invalid";
    assertThrows(IllegalArgumentException.class, config::validate);
    config.pgo = "use";
    assertThrows(IllegalArgumentException.class, config::validate);
    config.profile = directory.resolve("missing.iprof");
    assertThrows(IllegalArgumentException.class, config::validate);
    config.profile = directory;
    assertThrows(IllegalArgumentException.class, config::validate);
    config.profile = Files.writeString(directory.resolve("training.iprof"), "fixture");
    config.validate();
    config.pgo = "off";
    assertThrows(IllegalArgumentException.class, config::validate);
    config.profile = null;
    config.pgo = "instrument";
    config.validate();
    config.pgo = "off";
    config.classpath.add(config.home.resolve("smh.jar"));
    config.validate();
    config.classpath.add(directory.resolve("missing.jar"));
    assertThrows(IllegalArgumentException.class, config::validate);
    config.classpath.removeLast();
    config.sources.clear();
    assertThrows(IllegalArgumentException.class, config::validate);
    config.sources.add(directory.resolve("src"));
    Files.delete(config.home.resolve("smh.jar"));
    assertThrows(IllegalArgumentException.class, config::validate);
    Files.writeString(config.home.resolve("smh.jar"), "fixture");
    Files.delete(config.home.resolve("smh-runtime.jar"));
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void structuredFlagsCannotBeOverriddenThroughNativeArgs() throws Exception {
    BuildConfig config = validBuild();
    for (String arg :
        List.of(
            "-O1",
            "--pgo=bad",
            "-H:LayerCreate=x",
            "--layer-use=x",
            "--shared",
            "-cp",
            "-o",
            "-DcompilerBlackholesEnabled=false",
            "-J-DcompilerBlackholesEnabled=false",
            "@args")) {
      config.nativeArgs.clear();
      config.nativeArgs.add(arg);
      assertThrows(IllegalArgumentException.class, config::validate, arg);
    }
    config.nativeArgs.clear();
    config.nativeArgs.addAll(List.of("-g", "--gc=Serial", "--parallelism=1"));
    config.validate();
    config.nativeImage = List.of();
    assertThrows(IllegalArgumentException.class, config::validate);
    config.nativeImage = List.of("python", " ");
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void runDefaultsAndCompleteJson() throws Exception {
    assertEquals(".*", RunConfig.read(null).include);
    assertEquals(".*", RunConfig.read("").include);
    RunConfig.read(json("{}").toString());
    RunConfig config =
        RunConfig.read(
            json("""
            {"include":"Example","warmupIterations":0,"measurementIterations":2,"warmupNanos":1,
             "measurementNanos":3,"modes":["Throughput","AverageTime","SingleShotTime"],
             "params":{"size":["1","2"]},"output":"results.json"}
            """)
                .toString());
    assertEquals("Example", config.include);
    assertEquals(0, config.warmupIterations);
    assertEquals(2, config.measurementIterations);
    assertEquals(1L, config.warmupNanos);
    assertEquals(3L, config.measurementNanos);
    assertEquals(3, config.modes.size());
    assertEquals(List.of("1", "2"), config.params.get("size"));
    assertEquals(Path.of("results.json"), config.output);
  }

  @Test
  void runJsonShapesAndPrimitiveTypesAreValidatedWithoutCoercion() {
    assertThrows(IllegalArgumentException.class, () -> RunConfig.fromJson(null));
    for (String value : List.of("null", "true", "42", "\"text\"", "[]"))
      assertThrows(
          IllegalArgumentException.class, () -> RunConfig.fromJson(JsonParser.parseString(value)));
    for (String key : List.of("include", "backend", "output"))
      for (String value : List.of("null", "true", "42", "[]", "{}"))
        assertThrows(
            IllegalArgumentException.class,
            () -> RunConfig.fromJson(JsonParser.parseString("{\"" + key + "\":" + value + "}")));
    for (String key : List.of("wallBatchSize", "warmupIterations", "measurementNanos"))
      for (String value : List.of("null", "true", "\"2\"", "[]", "{}"))
        assertThrows(
            IllegalArgumentException.class,
            () -> RunConfig.fromJson(JsonParser.parseString("{\"" + key + "\":" + value + "}")));
    for (String key : List.of("warmupStability", "measurementStability", "params"))
      assertThrows(
          IllegalArgumentException.class,
          () -> RunConfig.fromJson(JsonParser.parseString("{\"" + key + "\":[]}")));
    for (String value :
        List.of(
            "null",
            "true",
            "42",
            "\"AverageTime\"",
            "{}",
            "[null]",
            "[true]",
            "[42]",
            "[{}]",
            "[[]]"))
      assertThrows(
          IllegalArgumentException.class,
          () -> RunConfig.fromJson(JsonParser.parseString("{\"modes\":" + value + "}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RunConfig.fromJson(JsonParser.parseString("{\"params\":{\"size\":42}}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RunConfig.fromJson(JsonParser.parseString("{\"params\":{\"size\":[42]}}")));
    assertEquals(
        Long.MAX_VALUE,
        RunConfig.fromJson(JsonParser.parseString("{\"measurementNanos\":9223372036854775807}"))
            .measurementNanos);
  }

  @Test
  void invalidRunConfigurationAndExactNumbers() throws Exception {
    for (String invalid :
        List.of(
            "{\"unknown\":1}",
            "{\"warmupIterations\":-1}",
            "{\"measurementIterations\":0}",
            "{\"warmupNanos\":0}",
            "{\"measurementNanos\":0}",
            "{\"modes\":[]}",
            "{\"modes\":[\"SampleTime\"]}",
            "{\"params\":{\"size\":[]}}",
            "{\"include\":\"[\"}"))
      assertThrows(
          IllegalArgumentException.class, () -> RunConfig.read(json(invalid).toString()), invalid);
    for (String invalid :
        List.of(
            "{\"warmupIterations\":0.5}",
            "{\"warmupIterations\":2147483648}",
            "{\"warmupNanos\":0.5}",
            "{\"measurementNanos\":9223372036854775808}"))
      assertThrows(
          ArithmeticException.class, () -> RunConfig.read(json(invalid).toString()), invalid);
    assertTrue(Values.bool("true"));
    assertFalse(Values.bool("false"));
    assertThrows(IllegalArgumentException.class, () -> Values.bool("yes"));
    assertEquals('a', Values.character("a"));
    assertThrows(IllegalArgumentException.class, () -> Values.character("ab"));
  }

  @Test
  void stabilizationPoliciesAreStrictAndCannotConflictWithFixedCounts() throws Exception {
    String policy =
        """
        {"minimumSamples":4,"maximumSamples":8,"windowSize":2,"consecutiveWindows":2,
         "maximumRelativeDeviation":0.02,"maximumRelativeDrift":0.01,"maximumRelativeSlope":0.03}
        """;
    RunConfig config =
        RunConfig.read(
            json("{\"warmupStability\":" + policy + ",\"measurementStability\":" + policy + "}")
                .toString());
    assertEquals(new StabilityPolicy(4, 8, 2, 2, 0.02, 0.01, 0.03), config.warmupStability);
    assertEquals(config.warmupStability, config.measurementStability);
    config.warmupIterations = 1;
    assertThrows(IllegalArgumentException.class, config::validate);
    config.warmupIterations = null;
    config.measurementIterations = 3;
    assertThrows(IllegalArgumentException.class, config::validate);
    config.measurementIterations = null;
    config.validate();
    assertThrows(
        IllegalArgumentException.class,
        () -> RunConfig.read(json("{\"warmupStability\":{}}").toString()));
    JsonObject extra = JsonParser.parseString(policy).getAsJsonObject();
    extra.addProperty("typo", 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> RunConfig.read(json("{\"measurementStability\":" + extra + "}").toString()));
    extra.remove("typo");
    extra.addProperty("minimumSamples", 4.5);
    assertThrows(
        ArithmeticException.class,
        () -> RunConfig.read(json("{\"measurementStability\":" + extra + "}").toString()));
  }

  @Test
  void benchmarkMetadataIsValidatedAndDefensivelyCopied() {
    Map<String, List<String>> params = new java.util.HashMap<>();
    List<String> values = new java.util.ArrayList<>(List.of("1"));
    params.put("size", values);
    List<String> modes = new java.util.ArrayList<>(List.of("AverageTime"));
    BenchmarkSpec spec =
        new BenchmarkSpec("name", params, modes, 0, 1, 1, 1, "NANOSECONDS", 1, p -> () -> {});
    values.add("2");
    params.clear();
    modes.clear();
    assertEquals(List.of("1"), spec.params().get("size"));
    assertEquals(List.of("AverageTime"), spec.modes());
    assertThrows(UnsupportedOperationException.class, () -> spec.params().put("x", List.of("y")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BenchmarkSpec(
                " ",
                Map.of(),
                List.of("AverageTime"),
                0,
                1,
                1,
                1,
                "NANOSECONDS",
                1,
                p -> () -> {}));
    for (int[] invalid :
        List.of(
            new int[] {-1, 1, 1, 1, 1},
            new int[] {0, 0, 1, 1, 1},
            new int[] {0, 1, 0, 1, 1},
            new int[] {0, 1, 1, 0, 1},
            new int[] {0, 1, 1, 1, 0}))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new BenchmarkSpec(
                  "name",
                  Map.of(),
                  List.of("AverageTime"),
                  invalid[0],
                  invalid[1],
                  invalid[2],
                  invalid[3],
                  "NANOSECONDS",
                  invalid[4],
                  p -> () -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BenchmarkSpec(
                "name", Map.of(), List.of(), 0, 1, 1, 1, "NANOSECONDS", 1, p -> () -> {}));
    for (Map<String, List<String>> invalid :
        List.of(Map.of(" ", List.of("x")), Map.<String, List<String>>of("x", List.of())))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new BenchmarkSpec(
                  "name",
                  invalid,
                  List.of("AverageTime"),
                  0,
                  1,
                  1,
                  1,
                  "NANOSECONDS",
                  1,
                  p -> () -> {}));
    assertThrows(
        NullPointerException.class,
        () ->
            new BenchmarkSpec(
                "name", Map.of(), List.of("AverageTime"), 0, 1, 1, 1, "NANOSECONDS", 1, null));
  }
}
