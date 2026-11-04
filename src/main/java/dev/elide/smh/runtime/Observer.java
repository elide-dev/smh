// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import java.util.Map;

/**
 * Observers bracket measured iterations. Trial/iteration fixtures and warmup are outside these
 * hooks; invocation fixtures are inside. This region differs from the current elapsed-time score.
 */
public interface Observer {
  default void beforeIteration(String benchmark, Map<String, String> params, int iteration) {}

  default void afterIteration(String benchmark, Map<String, String> params, int iteration) {}

  Observer NONE = new Observer() {};
}
