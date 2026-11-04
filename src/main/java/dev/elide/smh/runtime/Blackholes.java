// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import org.graalvm.nativeimage.ImageInfo;
import org.openjdk.jmh.infra.Blackhole;

/** JMH API adaptation; compiler installation and ordering require separate qualification. */
public final class Blackholes {
  private Blackholes() {}

  public static Blackhole create() {
    if (Boolean.getBoolean("compilerBlackholesEnabled") && !ImageInfo.inImageRuntimeCode())
      throw new IllegalStateException(
          "JVM compiler blackholes require JIT qualification; unset compilerBlackholesEnabled");
    return new Blackhole(
        "Today's password is swordfish. I understand instantiating Blackholes directly is"
            + " dangerous.");
  }

  /** Drop any captured object after the iteration, including exceptional exits. */
  public static void clear(Blackhole blackhole) {
    blackhole.evaporate("Yes, I am Stephen Hawking, and know a thing or two about black holes.");
  }
}
