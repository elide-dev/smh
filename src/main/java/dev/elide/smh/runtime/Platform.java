// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.runtime;

import java.util.Locale;
import java.util.Objects;

/** Platform naming decisions; native hardware qualification is a separate claim. */
public record Platform(OperatingSystem os, Architecture architecture) {
  public enum OperatingSystem {
    LINUX,
    MACOS,
    WINDOWS
  }

  public enum Architecture {
    AMD64,
    ARM64
  }

  public Platform {
    Objects.requireNonNull(os);
    Objects.requireNonNull(architecture);
  }

  public static Platform current() {
    return parse(System.getProperty("os.name"), System.getProperty("os.arch"));
  }

  public static Platform parse(String osName, String architectureName) {
    String system = osName.toLowerCase(Locale.ROOT);
    OperatingSystem os;
    if (system.equals("linux")) os = OperatingSystem.LINUX;
    else if (system.equals("mac os x") || system.equals("macos") || system.equals("darwin"))
      os = OperatingSystem.MACOS;
    else if (system.startsWith("windows")) os = OperatingSystem.WINDOWS;
    else throw new IllegalArgumentException("Unsupported operating system: " + osName);
    Architecture architecture =
        switch (architectureName.toLowerCase(Locale.ROOT)) {
          case "amd64", "x86_64", "x64" -> Architecture.AMD64;
          case "aarch64", "arm64" -> Architecture.ARM64;
          default ->
              throw new IllegalArgumentException("Unsupported architecture: " + architectureName);
        };
    return new Platform(os, architecture);
  }

  public String executable(String name) {
    checkName(name);
    return os == OperatingSystem.WINDOWS ? name + ".exe" : name;
  }

  public String targetTriple() {
    String cpu = architecture == Architecture.AMD64 ? "x86_64" : "aarch64";
    return cpu
        + switch (os) {
          case LINUX -> "-unknown-linux-gnu";
          case MACOS -> "-apple-darwin";
          case WINDOWS -> "-pc-windows-msvc";
        };
  }

  public static Platform fromTargetTriple(String target) {
    return switch (target) {
      case "x86_64-unknown-linux-gnu" -> new Platform(OperatingSystem.LINUX, Architecture.AMD64);
      case "aarch64-unknown-linux-gnu" -> new Platform(OperatingSystem.LINUX, Architecture.ARM64);
      case "x86_64-apple-darwin" -> new Platform(OperatingSystem.MACOS, Architecture.AMD64);
      case "aarch64-apple-darwin" -> new Platform(OperatingSystem.MACOS, Architecture.ARM64);
      case "x86_64-pc-windows-msvc" -> new Platform(OperatingSystem.WINDOWS, Architecture.AMD64);
      case "aarch64-pc-windows-msvc" -> new Platform(OperatingSystem.WINDOWS, Architecture.ARM64);
      default -> throw new IllegalArgumentException("Unsupported ABI target: " + target);
    };
  }

  public String sharedLibrary(String name) {
    checkName(name);
    return switch (os) {
      case LINUX -> "lib" + name + ".so";
      case MACOS -> "lib" + name + ".dylib";
      case WINDOWS -> name + ".dll";
    };
  }

  public String layerImageName(String name) {
    checkName(name);
    return os == OperatingSystem.WINDOWS ? name : "lib" + name;
  }

  public String loaderOrigin() {
    return switch (os) {
      case LINUX -> "$ORIGIN";
      case MACOS -> "@loader_path";
      case WINDOWS -> "";
    };
  }

  private static void checkName(String name) {
    if (name.isBlank()
        || name.contains("/")
        || name.contains("\\")
        || name.equals(".")
        || name.equals(".."))
      throw new IllegalArgumentException("Expected a simple artifact name: " + name);
  }
}
