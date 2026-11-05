// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("portable")
class CallgrindTest {
  @TempDir Path directory;

  private static String dump(int part, String trigger, String counts) {
    return "# callgrind format\nversion: 1\ncreator: callgrind-3.25.1\npid: 17\npart: "
        + part
        + "\nthread: 1\ndesc: I1 cache: 32768 B, 64 B, 8-way associative\ndesc: Trigger: "
        + trigger
        + "\npositions: line\nevents: Ir Dr Bc\nsummary: "
        + counts
        + "\nfl=(1) /fixture.c\nfn=(1) batch\n1 1\ntotals: "
        + counts
        + "\n";
  }

  private static String measured(int part, String counts) {
    return dump(part, "--dump-after=" + CallgrindRunner.REGION, counts);
  }

  private static JsonArray manifest(int iterations) {
    return JsonParser.parseString(
            "[{\"schemaVersion\":1,\"benchmark\":\"work\",\"backend\":\"callgrind\",\"status\":\"pending-counter-import\",\"measurementIterations\":"
                + iterations
                + ",\"operationsPerIteration\":3}]")
        .getAsJsonArray();
  }

  private Path file(Path dir, int part, String text) throws IOException {
    return Files.writeString(dir.resolve("callgrind.out.17." + part + "-01"), text);
  }

  @Test
  void parserRetainsExactUnsignedCountsAndImplicitTrailingZeros() {
    String value = "1208925819614629174706176";
    CallgrindDump parsed = CallgrindDump.parse(measured(1, value));
    assertEquals(new BigInteger(value), parsed.events().get("Ir"));
    assertEquals(BigInteger.ZERO, parsed.events().get("Bc"));
    assertEquals("32768 B, 64 B, 8-way associative", parsed.caches().get("I1"));
  }

  @Test
  void malformedOrContradictoryHeadersNeverProduceCounts() {
    String good = measured(1, "100 20 3");
    List<String> invalid =
        List.of(
            good.replace("version: 1", "version: 2"),
            good.replace("desc: Trigger:", "desc: Other:"),
            good.replace("creator: callgrind-3.25.1", "creator: fake"),
            good.replace("creator: callgrind-3.25.1", "creator: "),
            good.replace("creator: callgrind-3.25.1\n", ""),
            good.replace("events: Ir Dr Bc", "events: Dr Bc"),
            good.replace("events: Ir Dr Bc", "events: Ir Ir Bc"),
            good.replace("events: Ir Dr Bc", "events: Ir Dr ?"),
            good.replace("summary: 100 20 3", "summary: -1"),
            good.replace("summary: 100 20 3", "summary: NaN"),
            good.replace("summary: 100 20 3", "summary: 1 2 3 4"),
            good.replace("totals: 100 20 3", "totals: 100 20 4"),
            good.replace("pid: 17", "pid: 0"),
            good.replace("pid: 17", "pid: 999999999999999999"),
            good + "part: 1\n",
            good + "desc: Trigger: duplicate\n",
            good + "desc: I1 cache: duplicate\n");
    for (String text : invalid)
      assertThrows(IllegalArgumentException.class, () -> CallgrindDump.parse(text), text);
  }

  @Test
  void importsOnlyDeclaredRegionsWithExactRatiosAndAllRawSamples() throws Exception {
    BigInteger first = BigInteger.ONE.shiftLeft(80), second = first.add(BigInteger.ONE);
    file(directory, 1, measured(1, first + " 2"));
    file(directory, 2, measured(2, second + " 3 1"));
    Files.writeString(directory.resolve("callgrind.out.17"), "");
    Files.writeString(
        directory.resolve("callgrind.out.17-01"), dump(3, "Program termination", "0"));
    JsonObject report = CallgrindRunner.importResults(manifest(2), directory);
    JsonObject result = report.getAsJsonArray("results").get(0).getAsJsonObject();
    assertEquals("collected", result.get("status").getAsString());
    assertEquals("varies", result.get("instructionRepeatability").getAsString());
    assertEquals(
        first.add(second).toString(),
        result.getAsJsonObject("primaryMetric").get("numerator").getAsString());
    assertEquals("6", result.getAsJsonObject("primaryMetric").get("denominator").getAsString());
    assertEquals(
        first.toString(),
        result
            .getAsJsonArray("observations")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("events")
            .get("Ir")
            .getAsString());
    assertEquals(3, report.getAsJsonArray("rawFiles").size());
    file(directory, 2, measured(2, first + " 3"));
    assertEquals(
        "identical",
        CallgrindRunner.importResults(manifest(2), directory)
            .getAsJsonArray("results")
            .get(0)
            .getAsJsonObject()
            .get("instructionRepeatability")
            .getAsString());
    Files.delete(directory.resolve("callgrind.out.17.2-01"));
    assertEquals(
        "not-assessed",
        CallgrindRunner.importResults(manifest(1), directory)
            .getAsJsonArray("results")
            .get(0)
            .getAsJsonObject()
            .get("instructionRepeatability")
            .getAsString());
  }

  @Test
  void missingExtraLeakingMixedAndCorruptDumpsAreRejected() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> CallgrindRunner.importResults(new JsonArray(), directory));
    assertThrows(
        IllegalArgumentException.class,
        () -> CallgrindRunner.importResults(manifest(1), directory));
    Path truncated = Files.createDirectory(directory.resolve("truncated"));
    file(truncated, 1, "");
    assertThrows(
        IllegalArgumentException.class,
        () -> CallgrindRunner.importResults(manifest(1), truncated));
    for (String variant :
        List.of(
            "duplicate",
            "pid",
            "thread",
            "creator",
            "cache",
            "events",
            "outside",
            "unknown",
            "gap",
            "no-ir",
            "zero")) {
      Path dir = Files.createDirectory(directory.resolve(variant));
      file(dir, 1, measured(1, "10"));
      String other = measured(2, "10");
      other =
          switch (variant) {
            case "duplicate" -> measured(1, "10");
            case "pid" -> other.replace("pid: 17", "pid: 18");
            case "thread" -> other.replace("thread: 1", "thread: 2");
            case "creator" -> other.replace("callgrind-3.25.1", "callgrind-3.25.2");
            case "cache" -> other.replace("32768 B", "65536 B");
            case "events" -> other.replace("Ir Dr Bc", "Ir Dr Bi");
            case "outside" -> dump(2, "Program termination", "10");
            case "unknown" -> dump(2, "unknown", "0");
            case "gap" -> measured(3, "10");
            case "no-ir" -> measured(2, "0 1");
            default -> measured(2, "0");
          };
      file(dir, 2, other);
      assertThrows(
          IllegalArgumentException.class,
          () -> CallgrindRunner.importResults(manifest(2), dir),
          variant);
    }
    Path dir = Files.createDirectory(directory.resolve("budgets"));
    file(dir, 1, measured(1, "10"));
    for (String field :
        List.of(
            "backend", "status", "measurementIterations", "operationsPerIteration", "fraction")) {
      JsonArray invalid = manifest(1);
      JsonObject row = invalid.get(0).getAsJsonObject();
      if (field.equals("backend") || field.equals("status")) row.addProperty(field, "wrong");
      else if (field.equals("fraction")) row.addProperty("operationsPerIteration", 1.5);
      else row.addProperty(field, 0);
      assertThrows(
          RuntimeException.class, () -> CallgrindRunner.importResults(invalid, dir), field);
    }
    assertThrows(
        IllegalArgumentException.class, () -> CallgrindRunner.importResults(manifest(2), dir));
    file(dir, 2, measured(2, "11"));
    assertThrows(
        IllegalArgumentException.class, () -> CallgrindRunner.importResults(manifest(1), dir));
  }

  @Test
  void launcherPreservesEvidenceRequiresFreshOutputAndPropagatesProcessFailures() throws Exception {
    Path binary = Files.writeString(directory.resolve("binary"), "native fixture marker");
    Path config =
        Files.writeString(
            directory.resolve("run.json"),
            "{\"backend\":\"callgrind\",\"invocationsPerIteration\":3}");
    Platform linux = Platform.parse("Linux", "amd64");
    List<List<String>> commands = new ArrayList<>();
    CallgrindRunner.Commands fixture =
        (command, out, err) -> {
          commands.add(command);
          if (command.contains("--version")) Files.writeString(out, "valgrind-3.25.1\n");
          else {
            Files.writeString(out, "benchmark output");
            Files.writeString(out.getParent().resolve("execution.json"), manifest(1).toString());
            file(out.getParent(), 1, measured(1, "12"));
          }
          return 0;
        };
    CallgrindRunner runner = new CallgrindRunner(fixture, linux);
    Path output = directory.resolve("success");
    JsonObject report = runner.run(binary, config, output, "valgrind");
    assertTrue(Files.isRegularFile(output.resolve("results.json")));
    assertEquals("benchmark output", Files.readString(output.resolve("benchmark.stdout.log")));
    Path conflicting =
        Files.writeString(
            directory.resolve("conflicting.json"),
            "{\"backend\":\"callgrind\",\"invocationsPerIteration\":3,\"output\":\"other.json\"}");
    assertThrows(
        IllegalArgumentException.class,
        () -> runner.run(binary, conflicting, directory.resolve("conflicting"), "valgrind"));
    assertEquals(64, report.get("binarySha256").getAsString().length());
    assertTrue(commands.get(1).contains("--collect-atstart=no"));
    assertTrue(commands.get(1).contains("--separate-threads=yes"));
    assertEquals("unknown-build-exploration", report.get("purpose").getAsString());
    for (String pgo : List.of("off", "use", "instrument")) {
      Files.writeString(directory.resolve("build.json"), "{\"pgo\":\"" + pgo + "\"}");
      JsonObject variant = runner.run(binary, config, directory.resolve("pgo-" + pgo), "valgrind");
      assertEquals(
          pgo.equals("instrument") ? "instrumented-validation" : "exploratory-measurement",
          variant.get("purpose").getAsString());
      assertEquals(pgo, variant.getAsJsonObject("build").get("pgo").getAsString());
    }
    assertThrows(
        FileAlreadyExistsException.class, () -> runner.run(binary, config, output, "valgrind"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CallgrindRunner(fixture, Platform.parse("Windows", "arm64"))
                .run(binary, config, directory.resolve("win"), "valgrind"));
    Path wall = Files.writeString(directory.resolve("wall.json"), "{}");
    assertThrows(
        IllegalArgumentException.class,
        () -> runner.run(binary, wall, directory.resolve("wall"), "valgrind"));
    for (String failure : List.of("version-exit", "version-text", "execution")) {
      CallgrindRunner broken =
          new CallgrindRunner(
              (command, out, err) -> {
                if (command.contains("--version")) {
                  Files.writeString(
                      out, failure.equals("version-text") ? "wrong" : "valgrind-3.25.1");
                  return failure.equals("version-exit") ? 9 : 0;
                }
                return 7;
              },
              linux);
      Path dest = directory.resolve(failure);
      assertThrows(IOException.class, () -> broken.run(binary, config, dest, "valgrind"));
      assertFalse(Files.exists(dest.resolve("results.json")));
    }
    Main.Simulate cli = new Main.Simulate();
    cli.runner = runner;
    PrintStream previous = System.out;
    try (PrintStream capture = new PrintStream(new ByteArrayOutputStream())) {
      System.setOut(capture);
      assertEquals(
          0,
          new CommandLine(cli)
              .execute(
                  binary.toString(),
                  "--config",
                  config.toString(),
                  "--output",
                  directory.resolve("cli").toString()));
    } finally {
      System.setOut(previous);
    }
    Path java =
        Path.of(System.getProperty("java.home"), "bin", Platform.current().executable("java"));
    assertEquals(
        0,
        CallgrindRunner.execute(
            List.of(java.toString(), "--version"),
            directory.resolve("process.out"),
            directory.resolve("process.err")));
    assertFalse(Files.readString(directory.resolve("process.out")).isBlank());
  }

  @Test
  void soakImportsActualWorkAndRejectsIncompleteOrInvalidBudgets() throws Exception {
    file(directory, 1, measured(1, "60"));
    file(directory, 2, measured(2, "30"));
    Files.writeString(
        directory.resolve("callgrind.out.17-01"), dump(3, "Program termination", "0"));
    JsonArray manifest = manifest(2);
    JsonObject row = manifest.get(0).getAsJsonObject();
    row.addProperty("experimentMode", "soak");
    row.addProperty("requestedOperations", 4);
    row.add("operationCounts", JsonParser.parseString("[3,1]"));
    JsonObject result =
        CallgrindRunner.importResults(manifest, directory)
            .getAsJsonArray("results")
            .get(0)
            .getAsJsonObject();
    assertEquals("4", result.getAsJsonObject("primaryMetric").get("denominator").getAsString());
    assertEquals(22.5, result.getAsJsonObject("primaryMetric").get("score").getAsDouble());
    assertEquals("not-assessed", result.get("instructionRepeatability").getAsString());
    row.addProperty("requestedOperations", 6);
    row.add("operationCounts", JsonParser.parseString("[3,3]"));
    assertEquals(
        "varies",
        CallgrindRunner.importResults(manifest, directory)
            .getAsJsonArray("results")
            .get(0)
            .getAsJsonObject()
            .get("instructionRepeatability")
            .getAsString());
    row.addProperty("requestedOperations", 4);
    for (String counts : List.of("[3]", "[3,0]", "[3,4]", "[3,2]")) {
      row.add("operationCounts", JsonParser.parseString(counts));
      assertThrows(
          IllegalArgumentException.class,
          () -> CallgrindRunner.importResults(manifest, directory),
          counts);
    }
  }
}
