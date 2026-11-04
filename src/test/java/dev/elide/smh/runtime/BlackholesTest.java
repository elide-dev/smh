// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("portable")
class BlackholesTest {
  @Test
  void objectsCapturedByJmhAreClearedOutsideMeasurement() {
    var blackhole = Blackholes.create();
    Object value = new Object();
    blackhole.tlrMask = 0;
    blackhole.consume(value);
    assertInstanceOf(WeakReference.class, blackhole.obj1);
    assertSame(value, ((WeakReference<?>) blackhole.obj1).get());
    Blackholes.clear(blackhole);
    assertNull(blackhole.obj1);
  }

  @Test
  void unqualifiedJvmCompilerOnlyModeFailsInsteadOfRunningEmptyConsumes() {
    String previous = System.getProperty("compilerBlackholesEnabled");
    try {
      System.setProperty("compilerBlackholesEnabled", "true");
      assertTrue(
          assertThrows(IllegalStateException.class, Blackholes::create)
              .getMessage()
              .contains("JIT qualification"));
    } finally {
      if (previous == null) System.clearProperty("compilerBlackholesEnabled");
      else System.setProperty("compilerBlackholesEnabled", previous);
    }
    assertNotNull(Blackholes.create());
  }
}
