// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class StartupExperimentTest {
  @TempDir Path directory;

  Path script(String body) throws IOException {
    return Files.writeString(
        directory.resolve("Child.java"),
        """
        public class Child {
          public static void main(String[] args) throws Exception {
        """
            + body
            + "\n}}");
  }

  ProcessBuilder child(Path source, String... args) {
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        Platform.current().executable("java"))
                    .toString(),
                source.toString()));
    command.addAll(List.of(args));
    return new ProcessBuilder(command);
  }

  @Test
  void parentRequiresOneNotificationAndSuccessfulCleanupAndKeepsFailureEvidence() throws Exception {
    Path source =
        script(
            """
            String action=args[0];
            if(action.equals("sleep")) {Thread.sleep(30000);return;}
            System.out.println("diagnostic"); System.out.println();
            if(!action.equals("missing"))System.out.println("SMH_STARTUP "+System.getenv("SMH_STARTUP_TOKEN"));
            if(action.equals("duplicate"))System.out.println("SMH_STARTUP "+System.getenv("SMH_STARTUP_TOKEN"));
            if(action.equals("fail"))System.exit(2);
            System.out.println("[]");
            """);
    for (String action : List.of("ok", "exit", "missing", "duplicate", "fail", "sleep")) {
      Path run = Files.createDirectory(directory.resolve(action));
      if (action.equals("ok") || action.equals("exit")) {
        var launch =
            StartupExperiment.execute(
                child(source, action), run, 30_000_000_000L, "token", action.equals("exit"));
        assertTrue(launch.endpointNanos() > 0);
        assertTrue(launch.processId() > 0);
        assertEquals("[]", launch.result());
        if (action.equals("exit")) assertEquals(launch.endpointNanos(), launch.completionNanos());
      } else {
        assertThrows(
            Exception.class,
            () ->
                StartupExperiment.execute(
                    child(source, action),
                    run,
                    action.equals("sleep") ? 100_000_000L : 30_000_000_000L,
                    "token",
                    false));
        assertEquals(
            "failed",
            JsonParser.parseString(Files.readString(run.resolve("failure.json")))
                .getAsJsonObject()
                .get("status")
                .getAsString());
      }
      assertTrue(Files.exists(run.resolve("command.json")));
    }
    Path unreadable = Files.createDirectory(directory.resolve("unreadable"));
    Files.createDirectory(unreadable.resolve("stdout.log"));
    assertThrows(
        java.util.concurrent.ExecutionException.class,
        () ->
            StartupExperiment.execute(
                child(source, "ok"), unreadable, 30_000_000_000L, "token", false));
    assertThrows(
        IOException.class,
        () ->
            StartupExperiment.execute(
                new ProcessBuilder(directory.resolve("absent").toString()),
                directory,
                1_000_000_000L,
                "token",
                false));
  }

  static String dump(int part, String trigger, String count) {
    return "version: 1\ncreator: callgrind-3.25.1\npid: 17\npart: "
        + part
        + "\ndesc: I1 cache: 32768 B, 64 B, 8-way associative\ndesc: Trigger: "
        + trigger
        + "\nevents: Ir Dr\nsummary: "
        + count
        + "\ntotals: "
        + count
        + "\n";
  }

  @Test
  void processWideImportKeepsPostEndpointWorkAndRejectsAmbiguousScopes() throws Exception {
    String first = dump(1, "--dump-before=" + StartupExperiment.BOUNDARY, "100 20");
    String last = dump(2, "Program termination", "70 5");
    Path a = Files.writeString(directory.resolve("callgrind.out.17.1"), first);
    Path b = Files.writeString(directory.resolve("callgrind.out.17"), last);
    JsonObject result = StartupExperiment.importLaunch(directory, false);
    assertEquals("100", result.getAsJsonObject("events").get("Ir").getAsString());
    assertEquals("70", result.getAsJsonObject("postEndpointEvents").get("Ir").getAsString());
    assertEquals(2, result.getAsJsonArray("rawFiles").size());
    for (String bad :
        List.of(
            last.replace("part: 2", "part: 3"),
            last.replace("pid: 17", "pid: 18"),
            last.replace("Program termination", "wrong"),
            last.replace("3.25.1", "3.24.0"),
            last.replace("32768 B", "16384 B"),
            last.replace("Ir Dr", "Ir Dw"))) {
      Files.writeString(b, bad);
      assertThrows(IOException.class, () -> StartupExperiment.importLaunch(directory, false));
    }
    Files.writeString(b, last);
    Files.writeString(a, first.replace("100 20", "0 0"));
    assertThrows(IOException.class, () -> StartupExperiment.importLaunch(directory, false));
    Files.writeString(a, first + "thread: 1\n");
    assertThrows(
        IllegalArgumentException.class, () -> StartupExperiment.importLaunch(directory, false));
    Files.writeString(a, first);
    Files.writeString(b, last.replace("totals: 70 5", "totals: 65 4"));
    JsonObject full = StartupExperiment.importLaunch(directory, false);
    assertEquals("70", full.getAsJsonObject("postEndpointEvents").get("Ir").getAsString());
    assertEquals(
        "65", full.getAsJsonObject("postEndpointAttributedEvents").get("Ir").getAsString());
    assertEquals("100", full.getAsJsonObject("attributedEvents").get("Ir").getAsString());
    Files.writeString(b, last.replace("totals: 70 5", "totals: 71 5"));
    assertThrows(
        IllegalArgumentException.class, () -> StartupExperiment.importLaunch(directory, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> CallgrindDump.parse(last.replace("totals: 70 5", "totals: 65 4") + "thread: 1\n"));
    Files.delete(a);
    Files.writeString(b, dump(1, "Program termination", "100"));
    assertTrue(
        StartupExperiment.importLaunch(directory, true)
            .getAsJsonObject("postEndpointEvents")
            .isEmpty());
    assertThrows(IOException.class, () -> StartupExperiment.importLaunch(directory, false));
  }

  @Test
  void launcherClonesEnvironmentAndSelectsIndependentCaseConfigurations() throws Exception {
    Path source =
        script(
            """
            String description="[{'benchmark':'work','params':{},'jdkVersion':'test','vmName':'test VM'}]";
            if(!"1".equals(System.getenv("SMH_STARTUP_DISCOVER")))
              System.out.println("SMH_STARTUP "+System.getenv("SMH_STARTUP_TOKEN"));
            System.out.println(description);
            """);
    Path config =
        Files.writeString(directory.resolve("run.json"), "{\"mode\":\"startup\",\"launches\":2}");
    ProcessBuilder template = child(source, config.toString());
    template.environment().put("SMH_RESULT_STREAM", "should-remove");
    template.environment().put("SMH_EXECUTION_IDENTITY", "{\"backend\":\"hotspot\"}");
    ProcessBuilder copy = StartupExperiment.copy(template, config, directory.resolve("new.json"));
    assertFalse(copy.environment().containsKey("SMH_RESULT_STREAM"));
    assertEquals(config.toString(), template.command().getLast());
    assertThrows(
        IllegalArgumentException.class,
        () -> StartupExperiment.copy(template, directory.resolve("wrong"), config));
    JsonArray results =
        StartupExperiment.run(
            template,
            config,
            Files.createDirectory(directory.resolve("results")),
            "valgrind",
            TerminalDisplay.Mode.plain,
            null);
    assertEquals(
        2,
        results
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("smh")
            .getAsJsonArray("observations")
            .size());
    assertTrue(Files.exists(directory.resolve("results/results.json")));
    assertTrue(
        StartupExperiment.simulationCommand(
                "valgrind", List.of("binary"), directory, false, Platform.parse("Linux", "amd64"))
            .contains("--dump-before=" + StartupExperiment.BOUNDARY));
    assertFalse(
        StartupExperiment.simulationCommand(
                "valgrind", List.of("binary"), directory, true, Platform.parse("Linux", "amd64"))
            .contains("--dump-before=" + StartupExperiment.BOUNDARY));
  }

  @Test
  void orchestrationRetainsExactSimulationScoresAndRejectsChangedIdentity() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StartupExperiment.simulationCommand(
                "valgrind",
                List.of("binary"),
                directory,
                false,
                Platform.parse("Windows", "amd64")));
    JsonArray descriptions =
        JsonParser.parseString(
                "[{'benchmark':'work','params':{'size':'4'},'jdkVersion':'test','vmName':'test"
                    + " VM'}]")
            .getAsJsonArray();
    for (String scenario : List.of("normal", "exit", "empty", "identity", "changed", "model")) {
      Path target = Files.createDirectory(directory.resolve(scenario));
      Path config =
          Files.writeString(
              target.resolve("run.json"),
              "{\"mode\":\"startup\",\"backend\":\"callgrind\",\"launches\":2,\"timeoutNanos\":10,\"until\":\""
                  + (scenario.equals("exit") ? "exit" : "ready")
                  + "\"}");
      ProcessBuilder process = new ProcessBuilder("binary", config.toString());
      StartupExperiment.Launcher launcher =
          (builder, destination, timeout, token, exit) -> {
            if (builder.environment().containsKey("SMH_STARTUP_DISCOVER"))
              return new StartupExperiment.Launch(
                  1, 2, scenario.equals("empty") ? "[]" : descriptions.toString(), 17);
            String creator =
                scenario.equals("model") && destination.endsWith("launch-1") ? "3.24.0" : "3.25.1";
            Files.writeString(
                destination.resolve("callgrind.out.17"),
                dump(
                        1,
                        exit
                            ? "Program termination"
                            : "--dump-before=" + StartupExperiment.BOUNDARY,
                        "100 20")
                    .replace("3.25.1", creator));
            if (!exit)
              Files.writeString(
                  destination.resolve("callgrind.out.17.2"),
                  dump(2, "Program termination", "70 5").replace("3.25.1", creator));
            return new StartupExperiment.Launch(
                1,
                2,
                scenario.equals("identity")
                    ? "[]"
                    : scenario.equals("changed")
                        ? descriptions.toString().replace("work", "other")
                        : descriptions.toString(),
                17);
          };
      if (Platform.current().os() != Platform.OperatingSystem.LINUX && !scenario.equals("empty")) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                StartupExperiment.run(
                    process,
                    config,
                    target,
                    "valgrind",
                    TerminalDisplay.Mode.plain,
                    null,
                    launcher));
      } else if (List.of("normal", "exit").contains(scenario)) {
        JsonArray rows =
            StartupExperiment.run(
                process, config, target, "valgrind", TerminalDisplay.Mode.tui, null, launcher);
        assertEquals(
            100,
            rows.get(0).getAsJsonObject().getAsJsonObject("primaryMetric").get("score").getAsInt());
      } else
        assertThrows(
            IOException.class,
            () ->
                StartupExperiment.run(
                    process,
                    config,
                    target,
                    "valgrind",
                    TerminalDisplay.Mode.plain,
                    null,
                    launcher));
    }
    Path wall =
        Files.writeString(
            directory.resolve("wall.json"),
            "{\"mode\":\"startup\",\"until\":\"exit\",\"output\":\""
                + directory.resolve("export/result.json").toString().replace("\\", "/")
                + "\"}");
    JsonArray rows =
        StartupExperiment.run(
            new ProcessBuilder("binary", wall.toString()),
            wall,
            Files.createDirectory(directory.resolve("wall")),
            "valgrind",
            TerminalDisplay.Mode.plain,
            null,
            (builder, path, timeout, token, exit) ->
                new StartupExperiment.Launch(1, 2, descriptions.toString(), 17));
    assertEquals(
        "parent-launch-to-process-exit",
        rows.get(0).getAsJsonObject().getAsJsonObject("smh").get("boundary").getAsString());
    assertTrue(Files.exists(directory.resolve("export/result.json")));
    Path baseline = Files.writeString(directory.resolve("baseline.json"), rows.toString());
    StartupExperiment.run(
        new ProcessBuilder("binary", wall.toString()),
        wall,
        Files.createDirectory(directory.resolve("compared")),
        "valgrind",
        TerminalDisplay.Mode.tui,
        BenchmarkComparison.read(baseline),
        (builder, path, timeout, token, exit) ->
            new StartupExperiment.Launch(1, 2, descriptions.toString(), 17));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            StartupExperiment.run(
                new ProcessBuilder("binary", wall.toString()),
                wall,
                directory,
                "valgrind",
                TerminalDisplay.Mode.plain,
                BenchmarkComparison.read(baseline)));
  }
}
