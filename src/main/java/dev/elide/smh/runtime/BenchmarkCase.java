// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

/** Generated direct-call adapter; no benchmark reflection is required at runtime. */
public interface BenchmarkCase {
  default void setup(String level) throws Throwable {}

  default void tearDown(String level) throws Throwable {}

  default String sink() {
    return "none";
  }

  default boolean hasInvocationFixtures() {
    return false;
  }

  void invoke() throws Throwable;
}
