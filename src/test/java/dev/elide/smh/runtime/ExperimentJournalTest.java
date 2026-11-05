// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class ExperimentJournalTest {
  @TempDir Path directory;

  @Test
  void windowsSurviveLaterFailureWithoutBecomingSuccessfulResults() throws Throwable {
    RunConfig config =
        ExperimentTest.config(
            "{'mode':'soak','backend':'callgrind','operations':6,'invocationsPerIteration':1}");
    config.output = directory.resolve("nested/result.json");
    BenchmarkCase benchmark =
        new BenchmarkCase() {
          int calls;

          public void invoke() {
            if (++calls == 3) throw new IllegalStateException("late failure");
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> Runner.run(List.of(ExperimentTest.spec(benchmark)), config, Observer.NONE));
    assertFalse(Files.exists(config.output));
    List<String> lines = Files.readAllLines(Path.of(config.output + ".progress.jsonl"));
    assertEquals(3, lines.size());
    for (int i = 0; i < 2; i++)
      assertEquals(
          2,
          JsonParser.parseString(lines.get(i))
              .getAsJsonObject()
              .getAsJsonObject("observation")
              .get("operations")
              .getAsInt());
    assertEquals(
        "failed",
        JsonParser.parseString(lines.getLast())
            .getAsJsonObject()
            .getAsJsonObject("observation")
            .get("status")
            .getAsString());
    ExperimentJournal.record(new RunConfig(), "ignored", Map.of(), new JsonObject());
    config.output = null;
    ExperimentJournal.record(config, "ignored", Map.of(), new JsonObject());
  }
}
