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

@Tag("portable")
class ExperimentVisualsTest {
  @TempDir Path directory;

  static JsonObject row(boolean startup, boolean simulation) {
    JsonObject row =
        JsonParser.parseString(
                """
                {"benchmark":"example.start","mode":"avgt","params":{},
                 "primaryMetric":{"score":3,"scoreUnit":"ms/op","rawData":[[1,2,3,4,5]]},
                 "smh":{"backend":"wall","experimentMode":"soak","endpoint":"first-result",
                  "accuracy":"unqualified","measurement":{"observations":[]}, "observations":[]}}
                """)
            .getAsJsonObject();
    JsonObject smh = row.getAsJsonObject("smh");
    smh.addProperty("experimentMode", startup ? "startup" : "soak");
    smh.addProperty("backend", simulation ? "callgrind" : "wall");
    if (startup) {
      row.addProperty("mode", "startup");
      row.getAsJsonObject("primaryMetric")
          .addProperty("scoreUnit", simulation ? "instructions/launch" : "ms/launch");
    }
    if (simulation)
      smh.add("simulationModel", JsonParser.parseString("{'creator':'callgrind-3.25.1'}"));
    for (int i = 1; i <= 5; i++) {
      JsonObject observation = new JsonObject();
      observation.addProperty("score", i);
      observation.addProperty("endpointNanos", i * 1000000);
      observation.addProperty("completionNanos", i * 1000000 + 2000000);
      observation.add("postEndpointEvents", JsonParser.parseString("{'Ir':'2'}"));
      smh.getAsJsonArray("observations").add(observation);
      JsonObject window = new JsonObject();
      window.addProperty("endNanos", i * i * 1000000000L);
      smh.getAsJsonObject("measurement").getAsJsonArray("observations").add(window);
    }
    return row;
  }

  @Test
  void eachModeKeepsHistogramAndHistoryAtNarrowAndWideWidths() throws Exception {
    for (boolean startup : List.of(false, true))
      for (boolean simulation : List.of(false, true))
        for (int width : List.of(40, 58, 100, 140, 160)) {
          JsonObject row = row(startup, simulation);
          List<Double> samples = List.of(1., 2., 3., 4., 5.);
          String view = ResultDetails.render(row, samples, width);
          assertTrue(view.contains(startup ? "Launch distribution" : "Window distribution"));
          assertTrue(view.contains(startup ? "launch order" : "min–max per column"));
          assertTrue(view.contains(startup ? "p95" : "quarter median"));
          assertTrue(view.contains(TerminalDisplay.CYAN));
          for (String line : view.replaceAll("\033\\[[0-9;]*m", "").lines().toList())
            assertTrue(line.length() <= width, "width " + width + ": " + line);
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          TerminalDisplay.summary("[" + row + "]", new PrintStream(out), null, width);
          assertTrue(out.toString().contains("distribution"));
          Files.writeString(
              directory.resolve(
                  (startup ? "startup" : "soak") + "-" + simulation + "-" + width + ".ansi"),
              out.toString());
        }
    assertTrue(
        ExperimentVisuals.render(row(false, false), List.of(1.), 80)
            .contains("First → last window"));
    assertTrue(ExperimentVisuals.render(row(false, false), List.of(), 80).contains("Incomplete"));
    assertTrue(
        ExperimentVisuals.render(row(false, false), List.of(Double.NaN), 80)
            .contains("Incomplete"));
    JsonObject exit = row(true, true);
    for (JsonElement e : exit.getAsJsonObject("smh").getAsJsonArray("observations"))
      e.getAsJsonObject().add("postEndpointEvents", new JsonObject());
    assertTrue(ExperimentVisuals.render(exit, List.of(1., 2., 3., 4., 5.), 80).contains("After"));
    JsonObject simulation =
        JsonParser.parseString(
                """
                {"operationsPerIteration":100,"observations":[{"operations":"100","events":{"Ir":"200"}},{"operations":"50","events":{"Ir":"125"}}]}
                """)
            .getAsJsonObject();
    assertTrue(ExperimentVisuals.simulationSoak(simulation, 140).contains("Completed operations"));
    assertTrue(ExperimentVisuals.simulationSoak(simulation, 40).contains("Partial window"));
    simulation
        .getAsJsonArray("observations")
        .get(1)
        .getAsJsonObject()
        .addProperty("operations", 100);
    assertFalse(ExperimentVisuals.simulationSoak(simulation, 140).contains("Partial window"));
  }

  @Test
  void comparisonsRequireModeEndpointAndSimulatorIdentityAndKeepModeCharts() throws Exception {
    JsonObject startup = row(true, false);
    Path baseline = Files.writeString(directory.resolve("baseline.json"), "[" + startup + "]");
    BenchmarkComparison comparison = BenchmarkComparison.read(baseline);
    assertTrue(comparison.change(startup).isPresent());
    for (String key : List.of("experimentMode", "endpoint")) {
      JsonObject changed = startup.deepCopy();
      changed.getAsJsonObject("smh").addProperty(key, "different");
      assertTrue(comparison.change(changed).isEmpty());
    }
    JsonObject simulated = row(true, true);
    Files.writeString(baseline, "[" + simulated + "]");
    comparison = BenchmarkComparison.read(baseline);
    assertTrue(comparison.change(simulated).isPresent());
    simulated
        .getAsJsonObject("smh")
        .getAsJsonObject("simulationModel")
        .addProperty("creator", "changed");
    assertTrue(comparison.change(simulated).isEmpty());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    TerminalDisplay.summary("[" + startup + "]", new PrintStream(out), comparison, 140);
    assertTrue(out.toString().contains("Launch distribution"));
    assertTrue(out.toString().contains("of each row's metric"));
  }

  @Test
  void steadyAndUnadaptedRowsDoNotAcquireExperimentCharts() {
    JsonObject row = row(false, false);
    row.getAsJsonObject("smh").addProperty("experimentMode", "steady");
    JsonArray baseline = new JsonArray();
    baseline.add(row);
    BenchmarkComparison comparison = new BenchmarkComparison(baseline);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    TerminalDisplay.summary("[" + row + "]", new PrintStream(out), comparison, 80);
    assertFalse(out.toString().contains("Soak"));
    assertTrue(ResultDetails.render(row, List.of(1., 2., 3.), 80).contains("Iterations"));
    row.remove("smh");
    TerminalDisplay.summary("[" + row + "]", new PrintStream(out), comparison, 80);
    for (String backend : List.of("other", "callgrind")) {
      JsonObject invalid = row(true, true);
      invalid.getAsJsonObject("smh").addProperty("backend", backend);
      invalid.getAsJsonObject("smh").remove("simulationModel");
      JsonArray rows = new JsonArray();
      rows.add(invalid);
      assertThrows(IllegalArgumentException.class, () -> new BenchmarkComparison(rows));
    }
  }

  @Test
  void soakShowsUnsuccessfulWarmupStoppingOnceEvenWhenComparing() {
    JsonObject row = row(false, false);
    row.getAsJsonObject("smh")
        .add(
            "warmup",
            JsonParser.parseString("{'status':'UNSTABLE','stoppingReason':'MAX_SAMPLES'}"));
    assertTrue(
        ResultDetails.render(row, List.of(1., 2., 3., 4., 5.), 80)
            .contains("warmup · max samples"));
    JsonArray rows = new JsonArray();
    rows.add(row);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    TerminalDisplay.summary(
        rows.toString(), new PrintStream(out), new BenchmarkComparison(rows), 140);
    String text = out.toString();
    assertEquals(1, text.split("warmup · max samples", -1).length - 1);
    assertTrue(text.contains("Window distribution"));
  }
}
