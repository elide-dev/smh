// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh;

import static org.junit.jupiter.api.Assertions.*;

import dev.elide.smh.cli.*;
import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compiler planning tests; actual native builds remain separate qualification evidence. */
@Tag("portable")
class AotPlanningTest {
  @TempDir Path directory;

  private BuildConfig config() throws Exception {
    BuildConfig c = new BuildConfig();
    c.home = Files.createDirectories(directory.resolve("distribution"));
    Files.writeString(c.home.resolve("smh.jar"), "processor fixture");
    Files.writeString(c.home.resolve("smh-runtime.jar"), "runtime fixture");
    Path lib = Files.createDirectories(c.home.resolve("lib"));
    Files.writeString(lib.resolve("gson-0.jar"), "gson fixture");
    Files.writeString(lib.resolve("jmh-core-0.jar"), "jmh fixture");
    Files.writeString(lib.resolve("unrelated-0.jar"), "processor-only fixture");
    Files.writeString(lib.resolve("not-a-jar.txt"), "not part of classpath");
    Path sources = Files.createDirectories(directory.resolve("sources"));
    Files.writeString(sources.resolve("Work.java"), "public class Work {}");
    Files.writeString(sources.resolve("ignored.txt"), "ignored");
    c.sources.add(sources);
    c.output = directory.resolve("output");
    // The only command a dry run executes is --version: a real Java launcher suffices.
    c.nativeImage =
        List.of(
            Path.of(System.getProperty("java.home"), "bin", Platform.current().executable("java"))
                .toString());
    return c;
  }

  private record Plan(Path binary, String commands) {
    String key() {
      var matcher = Pattern.compile("[0-9a-f]{64}").matcher(commands);
      assertTrue(matcher.find(), commands);
      return matcher.group();
    }
  }

  private Plan plan(BuildConfig c) throws Exception {
    PrintStream original = System.err;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream captured = new PrintStream(output)) {
      System.setErr(captured);
      return new Plan(new AotBuilder().build(c, true), output.toString());
    } finally {
      System.setErr(original);
    }
  }

  @Test
  void optimizationPgoAndRuntimeContentAreIndependentLayerIdentityDimensions() throws Exception {
    BuildConfig c = config();
    Set<String> keys = new HashSet<>();
    for (String level : List.of("1", "2", "3")) {
      c.optimization = level;
      Plan plan = plan(c);
      assertTrue(keys.add(plan.key()));
      assertEquals(2, plan.commands.split("-O" + level, -1).length - 1);
      assertEquals(2, plan.commands.split("-DcompilerBlackholesEnabled=true", -1).length - 1);
      assertEquals(
          2,
          plan.commands.split(
                      java.util.regex.Pattern.quote(
                          "-H:NeverInline=dev.elide.smh.generated.Benchmarks$B*.invoke"),
                      -1)
                  .length
              - 1);
      assertEquals(
          2,
          plan.commands.split("--initialize-at-build-time=org.openjdk.jmh.infra.Blackhole", -1)
                  .length
              - 1);
      assertEquals(c.output.resolve(Platform.current().executable("benchmarks")), plan.binary);
      assertFalse(
          Files.exists(c.output.resolve("build.json")), "Dry run must not claim a built artifact");
    }
    Plan baseline = plan(c);
    Files.writeString(c.home.resolve("lib/unrelated-0.jar"), "changed processor-only dependency");
    assertEquals(baseline.key(), plan(c).key());
    Files.writeString(c.home.resolve("smh-runtime.jar"), "changed runtime");
    assertTrue(keys.add(plan(c).key()));
    Files.writeString(c.home.resolve("lib/gson-0.jar"), "changed runtime dependency");
    assertTrue(keys.add(plan(c).key()));
    c.nativeArgs.add("--gc=Serial");
    assertTrue(keys.add(plan(c).key()));
    c.pgo = "instrument";
    Plan instrument = plan(c);
    assertTrue(instrument.commands.contains("--pgo-instrument"));
    assertTrue(keys.add(instrument.key()));
    c.pgo = "use";
    c.profile = Files.writeString(directory.resolve("profile.iprof"), "profile-a");
    Plan use = plan(c);
    assertTrue(use.commands.contains("--pgo=" + c.profile));
    assertTrue(keys.add(use.key()));
    Files.writeString(c.profile, "profile-b");
    assertTrue(keys.add(plan(c).key()));
  }

  @Test
  void cachedBaseMonolithicSharedAndExplicitObserverPlanCorrectly() throws Exception {
    BuildConfig c = config();
    c.observer = "example.CustomObserver";
    c.classpath.add(c.home.resolve("smh.jar"));
    Plan first = plan(c);
    assertTrue(first.commands.contains("-Asmh.observer=example.CustomObserver"));
    Path layer = c.home.resolve("layers").resolve(first.key()).resolve("smh-base.nil");
    Files.createDirectories(layer.getParent());
    Files.writeString(layer, "cached-layer planning fixture");
    Files.writeString(
        layer.resolveSibling("SHA256"),
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(layer))));
    Plan cached = plan(c);
    assertFalse(cached.commands.contains("-H:LayerCreate"));
    assertTrue(cached.commands.contains("-H:LayerUse=" + layer));
    c.layers = false;
    c.shared = true;
    c.sources.clear();
    c.sources.add(directory.resolve("sources/Work.java"));
    Plan shared = plan(c);
    assertTrue(shared.commands.contains("--shared"));
    assertFalse(shared.commands.contains("-H:Layer"));
    assertEquals(c.output.resolve(Platform.current().sharedLibrary("benchmarks")), shared.binary);
  }

  @Test
  void missingEmptyAndNonJavaSourcesFailBeforeCompilerPlanning() throws Exception {
    BuildConfig c = config();
    c.sources.clear();
    c.sources.add(directory.resolve("absent"));
    assertThrows(IllegalArgumentException.class, () -> plan(c));
    c.sources.set(0, directory.resolve("sources/ignored.txt"));
    assertThrows(IllegalArgumentException.class, () -> plan(c));
    c.sources.set(0, Files.createDirectories(directory.resolve("empty")));
    assertThrows(IllegalArgumentException.class, () -> plan(c));
  }
}
