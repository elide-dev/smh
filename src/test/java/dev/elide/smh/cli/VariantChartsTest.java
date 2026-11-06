// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("portable")
class VariantChartsTest {
  @TempDir Path directory;

  static JsonObject row(String name, double... scores) {
    JsonObject row = ExperimentVisualsTest.row(true, false);
    row.getAsJsonObject("params").addProperty("lines", name);
    JsonArray samples = new JsonArray(), raw = new JsonArray();
    for (double score : scores) samples.add(score);
    raw.add(samples);
    row.getAsJsonObject("primaryMetric").add("rawData", raw);
    return row;
  }

  @Test
  void compatibleVariantsShareAxesWithoutPoolingOrHidingCollisions() throws Exception {
    JsonArray rows = new JsonArray();
    rows.add(row("10", 1, 1, 2, 3));
    rows.add(row("1000", 1, 2, 8));
    assertEquals(1, VariantCharts.groups(rows).size());
    assertTrue(VariantCharts.overlay(VariantCharts.groups(rows).getFirst()));
    JsonObject soak = rows.get(0).getAsJsonObject().deepCopy();
    soak.getAsJsonObject("smh").addProperty("experimentMode", "soak");
    assertFalse(VariantCharts.overlay(List.of(soak)));
    JsonObject bare = soak.deepCopy();
    bare.getAsJsonObject("smh").remove("experimentMode");
    assertFalse(VariantCharts.overlay(List.of(bare)));
    bare.remove("smh");
    assertFalse(VariantCharts.overlay(List.of(bare)));
    for (int width : List.of(40, 58, 100, 140)) {
      String view = VariantCharts.render(VariantCharts.groups(rows).getFirst(), width);
      assertTrue(view.contains("◇"));
      assertTrue(view.contains(TerminalDisplay.CYAN));
      assertTrue(view.contains(TerminalDisplay.VIOLET));
      assertTrue(view.contains("1 → 8 ms/launch"));
      assertTrue(view.contains("lines=10"));
      assertTrue(view.contains("n=3"));
      for (String line : view.replaceAll("\033\\[[0-9;]*m", "").lines().toList())
        assertTrue(line.length() <= width, "width " + width + ": " + line);
      Files.writeString(directory.resolve("variants-" + width + ".ansi"), view);
    }
    for (String field :
        List.of("backend", "endpoint", "execution", "simulationModel", "boundary")) {
      JsonArray different = rows.deepCopy();
      different.get(1).getAsJsonObject().getAsJsonObject("smh").addProperty(field, "different");
      assertEquals(2, VariantCharts.groups(different).size(), field);
    }
    List<JsonObject> many = new ArrayList<>();
    for (int i = 0; i < 7; i++) many.add(row("long variant ".repeat(9) + i, 5, 5));
    many.getFirst().remove("params");
    many.getFirst().addProperty("benchmark", "very.long.benchmark.".repeat(8));
    for (String endpoint : List.of("ready", "exit")) {
      many.getFirst().getAsJsonObject("smh").addProperty("endpoint", endpoint);
      assertTrue(
          VariantCharts.render(many, 80).contains(endpoint.equals("ready") ? "Ready" : "Full run"));
    }
    String paged = VariantCharts.render(many, 40);
    assertTrue(paged.contains("variant 1"));
    assertTrue(paged.contains("…"));
    assertEquals(2, paged.split("shared bins", -1).length - 1);
    assertTrue(VariantCharts.render(List.of(row("empty")), 80).contains("Incomplete"));
    assertTrue(VariantCharts.render(List.of(row("nan", Double.NaN)), 80).contains("Incomplete"));
    JsonArray regular = new JsonArray();
    JsonObject plain = row("plain", 1);
    plain.remove("smh");
    regular.add(plain);
    assertEquals(1, VariantCharts.groups(regular).size());
    assertTrue(VariantCharts.render(List.of(plain), 80).contains("plain"));
  }

  @Test
  void liveSnapshotsReplaceFramesAndRestoreTerminalOnce() throws Exception {
    assertEquals(24, LiveReport.height(Map.of("LINES", "invalid")));
    assertEquals(24, LiveReport.height(Map.of()));
    assertEquals(35, LiveReport.height(Map.of("LINES", "35")));
    var buffer = new ByteArrayOutputStream();
    var output = new PrintStream(buffer);
    JsonArray snapshot = new JsonArray();
    snapshot.add(row("10", 1, 2));
    try (var disabled = new LiveReport(output, 80, false)) {
      disabled.update(snapshot, "hidden");
    }
    assertEquals("", buffer.toString());
    Path stream = Files.createDirectory(directory.resolve("cases"));
    try (var live = new LiveReport(output, 80, true)) {
      assertEquals(0, TerminalDisplay.drainLive(stream, 0, live));
      Files.writeString(stream.resolve("0.json"), snapshot.get(0).toString());
      assertEquals(1, TerminalDisplay.drainLive(stream, 0, live));
      snapshot.add(row("1000", 1, 4));
      live.update(snapshot, "Startup · round 2/3");
      live.close();
    }
    String screen = buffer.toString();
    assertEquals(1, screen.split("\u001b\\[\\?1049h", -1).length - 1);
    assertEquals(1, screen.split("\u001b\\[\\?1049l", -1).length - 1);
    assertTrue(screen.contains("shared bins"));
    assertTrue(screen.contains("cases complete"));
  }

  @Test
  void startupRotatesCasesAndPublishesSnapshotsBeforeTheNextLaunch() throws Exception {
    JsonArray cases =
        JsonParser.parseString(
                "[{benchmark:'Invoice.total',params:{lines:'10'}},{benchmark:'Invoice.total',params:{lines:'1000'}}]")
            .getAsJsonArray();
    Path config = Files.writeString(directory.resolve("run.json"), "{mode:'startup',launches:3}");
    List<String> order = new ArrayList<>();
    Path output = Files.createDirectory(directory.resolve("rounds"));
    JsonArray results =
        StartupExperiment.run(
            new ProcessBuilder("binary", config.toString()),
            config,
            output,
            "valgrind",
            TerminalDisplay.Mode.plain,
            null,
            (builder, folder, timeout, token, exit) -> {
              if (builder.environment().containsKey("SMH_STARTUP_DISCOVER"))
                return new StartupExperiment.Launch(1, 2, cases.toString(), 1);
              int index =
                  Integer.parseInt(folder.getParent().getFileName().toString().substring(5));
              if (!order.isEmpty())
                assertTrue(Files.exists(output.resolve("partial-results.json")));
              order.add(index + ":" + folder.getFileName());
              return new StartupExperiment.Launch(
                  1_000_000L, 2_000_000L, "[" + cases.get(index) + "]", 42);
            });
    assertEquals(
        List.of("0:launch-0", "1:launch-0", "1:launch-1", "0:launch-1", "0:launch-2", "1:launch-2"),
        order);
    assertEquals(2, results.size());
    assertEquals(3, VariantCharts.samples(results.get(0).getAsJsonObject()).size());
    assertEquals(
        1,
        results
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("smh")
            .getAsJsonArray("observations")
            .get(1)
            .getAsJsonObject()
            .get("slot")
            .getAsInt());
  }

  @Test
  void directArgvCandidatesKeepFlagsAndRejectUnsupportedContracts() throws Exception {
    Main root = new Main();
    new CommandLine(root)
        .parseArgs(
            "--mode",
            "startup",
            "--launches",
            "1",
            "--candidate",
            "elide",
            "run",
            "a file.js",
            "--flag",
            "--candidate",
            "bun",
            "run",
            "a file.js");
    assertEquals("[\"elide\",\"run\",\"a file.js\",\"--flag\"]", root.candidates.getFirst());
    assertThrows(
        CommandLine.ParameterException.class,
        () -> new CommandLine(new Main()).parseArgs("--candidate"));
    List<String> candidates = root.candidates;
    ExperimentOptions options = new ExperimentOptions();
    assertThrows(
        IllegalArgumentException.class,
        () -> StartupCandidates.run(candidates, options, TerminalDisplay.Mode.plain));
    options.mode = "startup";
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StartupCandidates.run(
                List.of(candidates.getFirst()), options, TerminalDisplay.Mode.plain));
    options.until = "ready";
    assertThrows(
        IllegalArgumentException.class,
        () -> StartupCandidates.run(candidates, options, TerminalDisplay.Mode.plain));
    options.until = "exit";
    options.backend = "callgrind";
    assertThrows(
        IllegalArgumentException.class,
        () -> StartupCandidates.run(candidates, options, TerminalDisplay.Mode.plain));
    options.backend = "wall";
    options.launches = 2;
    options.timeout = "10s";
    options.output = directory.resolve("commands");
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                dev.elide.smh.runtime.Platform.current().executable("java"))
            .toString();
    String command = new Gson().toJson(List.of(java, "-version"));
    assertEquals(
        0,
        StartupCandidates.run(List.of(command, command), options, TerminalDisplay.Mode.tui, true));
    JsonArray result =
        JsonParser.parseString(Files.readString(options.output.resolve("results.json")))
            .getAsJsonArray();
    assertEquals(2, result.size());
    assertEquals(2, VariantCharts.samples(result.get(0).getAsJsonObject()).size());
    assertTrue(
        Files.readString(options.output.resolve("candidate-0/launch-0/stderr.log"))
            .contains("version"));
    assertTrue(StartupCandidates.fastest(result).contains("Fastest observed"));
    result.get(0).getAsJsonObject().getAsJsonObject("primaryMetric").addProperty("score", 1);
    result.get(1).getAsJsonObject().getAsJsonObject("primaryMetric").addProperty("score", 1);
    assertTrue(StartupCandidates.fastest(result).contains("No observed winner"));
  }
}
