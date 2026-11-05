// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

@Tag("portable")
class ExperimentOptionsTest {
  @TempDir Path directory;

  @Test
  void durationUnitsAreExactAndBounded() {
    String[] units = {"ns", "us", "ms", "s", "m", "h"};
    long[] scale = {1, 1000, 1000000, 1000000000, 60000000000L, 3600000000000L};
    for (int i = 0; i < units.length; i++)
      assertEquals(scale[i], ExperimentOptions.nanos("1" + units[i]));
    assertEquals(1500000000, ExperimentOptions.nanos("1.5s"));
    for (String invalid : new String[] {"0s", "-1s", "1", "1d", "NaNs"})
      assertThrows(IllegalArgumentException.class, () -> ExperimentOptions.nanos(invalid));
    for (String invalid : new String[] {"0.5ns", "999999999999999999999h"})
      assertThrows(ArithmeticException.class, () -> ExperimentOptions.nanos(invalid));
  }

  @Test
  void cliOverridesInheritedPoliciesAndRetainsRequestedDocument() throws Exception {
    JsonObject requested =
        JsonParser.parseString(
                "{'warmupIterations':2,'measurementIterations':3,'measurementNanos':100,'modes':['AverageTime']}")
            .getAsJsonObject();
    JsonObject original = requested.deepCopy();
    ExperimentOptions options = new ExperimentOptions();
    new CommandLine(options)
        .parseArgs("--mode", "startup", "--until", "ready", "--launches", "3", "--timeout", "2s");
    JsonObject effective = options.apply(requested);
    assertEquals(original, requested);
    assertFalse(effective.has("warmupIterations"));
    assertEquals(2000000000, effective.get("timeoutNanos").getAsLong());
    assertEquals("ready", effective.get("until").getAsString());
    options = new ExperimentOptions();
    new CommandLine(options).parseArgs("--mode", "soak", "--duration", "1s");
    assertFalse(options.apply(requested).has("measurementIterations"));
    options = new ExperimentOptions();
    new CommandLine(options)
        .parseArgs("--mode", "soak", "--backend", "callgrind", "--operations", "123");
    effective = options.apply(requested);
    assertEquals(10000, effective.get("invocationsPerIteration").getAsLong());
    assertFalse(effective.has("modes"));
    requested.addProperty("invocationsPerIteration", 7);
    assertEquals(7, options.apply(requested).get("invocationsPerIteration").getAsInt());
    options = new ExperimentOptions();
    new CommandLine(options).parseArgs("--mode", "startup", "--backend", "callgrind");
    assertFalse(options.apply(requested).has("invocationsPerIteration"));
    assertEquals(new JsonObject(), new ExperimentOptions().apply(new JsonObject()));
    ExperimentOptions wall = new ExperimentOptions();
    wall.backend = "wall";
    assertEquals("wall", wall.apply(new JsonObject()).get("backend").getAsString());
    ExperimentOptions invalid = new ExperimentOptions();
    invalid.mode = "oops";
    assertThrows(IllegalArgumentException.class, () -> invalid.apply(new JsonObject()));
    options.output = directory.resolve("new/run");
    assertTrue(Files.isDirectory(options.directory()));
    assertThrows(FileAlreadyExistsException.class, options::directory);
    assertTrue(Files.isDirectory(new ExperimentOptions().directory()));
  }
}
