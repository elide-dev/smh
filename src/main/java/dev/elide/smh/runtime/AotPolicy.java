// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import java.util.List;

/**
 * Shared by production builds and compiler qualification; flags request, rather than prove,
 * behavior.
 */
public final class AotPolicy {
  private AotPolicy() {}

  public static List<String> requiredCompilerFlags() {
    return List.of(
        "-DcompilerBlackholesEnabled=true",
        "--initialize-at-build-time=org.openjdk.jmh.infra.Blackhole",
        "-H:+UnlockExperimentalVMOptions",
        "-H:NeverInline=dev.elide.smh.generated.Benchmarks$B*.invoke,dev.elide.smh.runtime.SimulationRunner.measureBatch,dev.elide.smh.runtime.Startup.reached");
  }
}
