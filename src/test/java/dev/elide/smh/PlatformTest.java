// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh;

import static org.junit.jupiter.api.Assertions.*;

import dev.elide.smh.cli.BuildConfig;
import dev.elide.smh.runtime.Platform;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("portable")
class PlatformTest {
  @Test
  void aliasesAndAllPlatformArtifactConventions() {
    String[] operatingSystems = {"Linux", "Mac OS X", "macOS", "Darwin", "Windows 11"};
    String[] architectures = {"amd64", "x86_64", "x64", "aarch64", "arm64"};
    for (String system : operatingSystems)
      for (String architecture : architectures) {
        var platform = Platform.parse(system, architecture);
        assertEquals(
            architecture.startsWith("a") && !architecture.equals("amd64")
                ? Platform.Architecture.ARM64
                : Platform.Architecture.AMD64,
            platform.architecture());
        switch (platform.os()) {
          case LINUX -> {
            assertEquals("suite", platform.executable("suite"));
            assertEquals("libsuite.so", platform.sharedLibrary("suite"));
            assertEquals("libsuite", platform.layerImageName("suite"));
            assertEquals("$ORIGIN", platform.loaderOrigin());
            assertEquals("native-image", new BuildConfig(platform).nativeImage.getFirst());
          }
          case MACOS -> {
            assertEquals("suite", platform.executable("suite"));
            assertEquals("libsuite.dylib", platform.sharedLibrary("suite"));
            assertEquals("libsuite", platform.layerImageName("suite"));
            assertEquals("@loader_path", platform.loaderOrigin());
          }
          case WINDOWS -> {
            assertEquals("suite.exe", platform.executable("suite"));
            assertEquals("suite.dll", platform.sharedLibrary("suite"));
            assertEquals("suite", platform.layerImageName("suite"));
            assertEquals("", platform.loaderOrigin());
            assertEquals(
                java.util.List.of("cmd.exe", "/d", "/c", "native-image.cmd"),
                new BuildConfig(platform).nativeImage);
          }
        }
      }
    assertNotNull(Platform.current());
  }

  @Test
  void unsupportedAndAmbiguousNamesFailExplicitly() {
    assertThrows(IllegalArgumentException.class, () -> Platform.parse("Plan 9", "amd64"));
    assertThrows(IllegalArgumentException.class, () -> Platform.parse("Linux", "i386"));
    assertThrows(NullPointerException.class, () -> new Platform(null, Platform.Architecture.AMD64));
    assertThrows(
        NullPointerException.class, () -> new Platform(Platform.OperatingSystem.LINUX, null));
    var platform = Platform.parse("LINUX", "AMD64");
    for (String invalid : new String[] {"", " ", ".", "..", "a/b", "a\\b"}) {
      assertThrows(IllegalArgumentException.class, () -> platform.executable(invalid));
      assertThrows(IllegalArgumentException.class, () -> platform.sharedLibrary(invalid));
      assertThrows(IllegalArgumentException.class, () -> platform.layerImageName(invalid));
    }
  }
}
