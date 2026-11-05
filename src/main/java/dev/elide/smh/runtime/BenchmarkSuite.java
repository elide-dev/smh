// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import java.util.List;

/** Shared host contract. Implementations and benchmark dependencies may be runtime loaded. */
public interface BenchmarkSuite {
  List<BenchmarkSpec> benchmarks();

  Observer observer();
}
