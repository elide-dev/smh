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
