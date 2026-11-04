// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.fixtures;

import dev.elide.smh.runtime.Observer;
import java.util.Map;

public class CountingObserver implements Observer {
  public static int before, after;

  @Override
  public void beforeIteration(String benchmark, Map<String, String> params, int iteration) {
    before++;
  }

  @Override
  public void afterIteration(String benchmark, Map<String, String> params, int iteration) {
    after++;
  }
}
