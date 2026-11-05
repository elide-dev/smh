// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.jmh;

import com.google.gson.*;
import java.nio.file.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;

/** JVM-only entry point; JMH owns its generated harness, forks, timing and result format. */
public final class JmhJob {
  private JmhJob() {}

  public static Options options(JsonObject job) throws Exception {
    return new OptionsBuilder()
        .parent(new CommandLineOptions(strings(job.getAsJsonArray("arguments"))))
        .jvmArgs(strings(job.getAsJsonArray("runtimeArgs")))
        .jvmArgsAppend(new String[0])
        .jvmArgsPrepend(new String[0])
        .build();
  }

  private static String[] strings(JsonArray values) {
    return values.asList().stream().map(JsonElement::getAsString).toArray(String[]::new);
  }

  public static void main(String[] args) throws Exception {
    new Runner(
            options(JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject()))
        .run();
  }
}
