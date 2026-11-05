// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("portable")
class ResultPublisherTest {
  @TempDir Path directory;

  @Test
  void executionIdentityAndFlagsArePreservedWithoutAliasingRows() throws Exception {
    var publisher =
        new ResultPublisher(
            null, "{\"backend\":\"hotspot-c2\",\"runtimeArgs\":[\"-XX:-TieredCompilation\"]}");
    JsonObject first = new JsonObject();
    first.add("smh", new JsonObject());
    publisher.publish(first);
    assertEquals("-XX:-TieredCompilation", first.getAsJsonArray("jvmArgs").get(0).getAsString());
    first.getAsJsonObject("smh").getAsJsonObject("execution").addProperty("backend", "changed");
    JsonObject second = new JsonObject();
    second.add("smh", new JsonObject());
    publisher.publish(second);
    assertEquals(
        "hotspot-c2",
        second.getAsJsonObject("smh").getAsJsonObject("execution").get("backend").getAsString());
    assertThrows(JsonParseException.class, () -> new ResultPublisher(null, "not json"));
  }

  @Test
  void completedReportsPublishAtomicallyInOrderAndFailuresPropagate() throws Exception {
    var row = new JsonObject();
    row.addProperty("benchmark", "one");
    assertSame(row, new ResultPublisher(null).publish(row));
    var publisher = new ResultPublisher(directory.toString());
    assertSame(row, publisher.publish(row));
    assertEquals(row, JsonParser.parseString(Files.readString(directory.resolve("0.json"))));
    assertFalse(Files.exists(directory.resolve("pending.json")));
    row.addProperty("benchmark", "two");
    publisher.publish(row);
    assertTrue(Files.readString(directory.resolve("1.json")).contains("two"));
    assertThrows(
        java.io.IOException.class,
        () -> new ResultPublisher(directory.resolve("missing").toString()).publish(row));
  }
}
