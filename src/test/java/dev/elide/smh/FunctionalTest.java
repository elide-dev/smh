// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Exercise the public compiler and runtime workflows through the project test command. */
public class FunctionalTest {
  @Test
  void annotationProcessingAndRuntime() throws Exception {
    Process process =
        new ProcessBuilder(
                System.getProperty("os.name").startsWith("Windows") ? "python" : "python3",
                "tools/test.py")
            .directory(Path.of("").toAbsolutePath().toFile())
            .inheritIO()
            .start();
    assertEquals(0, process.waitFor(), "Functional integration checks failed");
  }
}
