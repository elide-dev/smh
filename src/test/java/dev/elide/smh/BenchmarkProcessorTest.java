// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import dev.elide.smh.processor.BenchmarkProcessor;
import dev.elide.smh.runtime.*;
import java.io.File;
import java.net.JarURLConnection;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.PointerBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.openjdk.jmh.annotations.Benchmark;

/** Real in-process javac runs: diagnostics, generated code, and executable behavior. */
@Tag("portable")
class BenchmarkProcessorTest {
  @TempDir Path directory;
  int invocation;

  private record Compilation(boolean success, String diagnostics, Path classes, Path generated) {}

  private Compilation compile(String source, String... options) throws Exception {
    return compile(Map.of("example/Work.java", source), options);
  }

  private Compilation compile(Map<String, String> sources, String... options) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "Portable processor tests require a full JDK");
    Path work = directory.resolve("compilation-" + invocation++);
    Path classes = Files.createDirectories(work.resolve("classes"));
    Path generated = Files.createDirectories(work.resolve("generated"));
    List<Path> files = new ArrayList<>();
    for (var entry : sources.entrySet()) {
      Path path = work.resolve(entry.getKey());
      Files.createDirectories(path.getParent());
      files.add(Files.writeString(path, entry.getValue()));
    }
    Set<String> classpath = new LinkedHashSet<>();
    for (Class<?> dependency :
        List.of(
            BenchmarkSpec.class,
            Benchmark.class,
            IsolateThread.class,
            PointerBase.class,
            JsonArray.class)) {
      var origin = dependency.getProtectionDomain().getCodeSource().getLocation();
      if (origin.getProtocol().equals("file")) classpath.add(Path.of(origin.toURI()).toString());
      // GraalVM may load SDK classes from jrt modules, which --release deliberately hides.
      // Use the actual SDK dependency JARs also available to the test classloader.
      var resources =
          getClass()
              .getClassLoader()
              .getResources(dependency.getName().replace('.', '/') + ".class");
      while (resources.hasMoreElements()) {
        var resource = resources.nextElement();
        if (resource.getProtocol().equals("jar"))
          classpath.add(
              Path.of(((JarURLConnection) resource.openConnection()).getJarFileURL().toURI())
                  .toString());
      }
    }
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
      List<String> flags =
          new ArrayList<>(
              List.of(
                  "--release",
                  "25",
                  "-classpath",
                  String.join(File.pathSeparator, classpath),
                  "-d",
                  classes.toString(),
                  "-s",
                  generated.toString()));
      flags.addAll(List.of(options));
      JavaCompiler.CompilationTask task =
          compiler.getTask(
              null, manager, diagnostics, flags, null, manager.getJavaFileObjectsFromPaths(files));
      task.setProcessors(List.of(new BenchmarkProcessor()));
      boolean success = task.call();
      return new Compilation(success, diagnostics.getDiagnostics().toString(), classes, generated);
    }
  }

  private static String work(String declaration, String body) {
    return "package example; import org.openjdk.jmh.annotations.*; "
        + declaration
        + " public class Work { "
        + body
        + " }";
  }

  private void rejects(String source, String message) throws Exception {
    Compilation result = compile(source);
    assertFalse(result.success, source);
    assertTrue(result.diagnostics.contains(message), result.diagnostics);
  }

  @SuppressWarnings("unchecked")
  @Test
  void generatedAdaptersExecuteEveryParameterTypeAndInheritedLifecycleInOrder() throws Throwable {
    Compilation result =
        compile(
            """
            package example;
            import org.openjdk.jmh.annotations.*;
            import java.util.concurrent.TimeUnit;
            @State(Scope.Thread) @Deprecated
            @Warmup(iterations=2,time=3,timeUnit=TimeUnit.NANOSECONDS)
            @Measurement(iterations=4,time=5,timeUnit=TimeUnit.NANOSECONDS)
            @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
            @OperationsPerInvocation(7) @Threads(1) @Fork(1)
            public class Work extends WorkBase {
              @Param({"true"}) public boolean bool;
              @Param({"2"}) public byte small;
              @Param({"3"}) public short medium;
              @Param({"4"}) public int number;
              @Param({"5"}) public long big;
              @Param({"1.5"}) public float fraction;
              @Param({"2.5"}) public double precise;
              @Param({"z"}) public char letter;
              @Param({"quote\\\"\\\\tab\\tline\\nreturn\\r"}) public String text;
              public enum Kind { FIRST, SECOND }
              @Param public Kind kind;
              public static final java.util.List<String> events = new java.util.ArrayList<>();
              @Setup(Level.Trial) public void setup() { events.add("sub.setup"); }
              @TearDown(Level.Trial) public void teardown() { events.add("sub.end"); }
              @Benchmark public void work(Extra extra) {
                if (!bool || small != 2 || medium != 3 || number != 4 || big != 5
                    || fraction != 1.5f || precise != 2.5 || letter != 'z'
                    || !text.equals("quote\\\"\\\\tab\\tline\\nreturn\\r") || kind != Kind.SECOND || extra.other != 8)
                  throw new AssertionError("Parameter conversion");
                events.add("work");
              }
              @State(Scope.Benchmark) public static class Extra {
                @Param({"8"}) public int other;
                @Setup(Level.Trial) public void setup() { events.add("extra.setup"); }
                @TearDown(Level.Trial) public void teardown() { events.add("extra.end"); }
                @Setup(Level.Invocation) public void prepareInvocation() {}
              }
            }
            class WorkBase {
              @Setup(Level.Trial) public void baseSetup() { Work.events.add("base.setup"); }
              @TearDown(Level.Trial) public void baseEnd() { Work.events.add("base.end"); }
            }
            """);
    assertTrue(result.success, result.diagnostics);
    try (URLClassLoader loader =
        new URLClassLoader(
            new java.net.URL[] {result.classes.toUri().toURL()}, getClass().getClassLoader())) {
      List<BenchmarkSpec> specs =
          (List<BenchmarkSpec>)
              loader.loadClass("dev.elide.smh.generated.Benchmarks").getMethod("all").invoke(null);
      assertEquals(1, specs.size());
      BenchmarkSpec spec = specs.getFirst();
      assertEquals(List.of("FIRST", "SECOND"), spec.params().get("kind"));
      assertEquals(2, spec.warmupIterations());
      assertEquals(4, spec.measurementIterations());
      assertEquals(3, spec.warmupNanos());
      assertEquals(5, spec.measurementNanos());
      assertEquals(7, spec.operationsPerInvocation());
      assertEquals("MICROSECONDS", spec.timeUnit());
      Map<String, String> params = new HashMap<>();
      spec.params().forEach((key, values) -> params.put(key, values.getLast()));
      BenchmarkCase benchmark = spec.factory().apply(params);
      assertTrue(
          benchmark.hasInvocationFixtures(),
          "Invocation fixtures must be declared for batch backend checks");
      benchmark.setup("Trial");
      benchmark.setup("Iteration");
      benchmark.invoke();
      benchmark.tearDown("Iteration");
      benchmark.tearDown("Trial");
      assertEquals(
          List.of(
              "base.setup", "sub.setup", "extra.setup", "work", "extra.end", "sub.end", "base.end"),
          loader.loadClass("example.Work").getField("events").get(null));
    }
  }

  @Test
  void defaultsOverridesEmptyRoundsAndObserverEntrypointCompile() throws Exception {
    Compilation empty = compile(work("@State(Scope.Thread)", ""));
    assertTrue(empty.success, empty.diagnostics);
    assertFalse(Files.exists(empty.generated.resolve("dev/elide/smh/generated/Benchmarks.java")));
    Compilation defaults = compile(work("", "@Benchmark public int run() { return 1; }"));
    assertTrue(defaults.success, defaults.diagnostics);
    String registry =
        Files.readString(defaults.generated.resolve("dev/elide/smh/generated/Benchmarks.java"));
    assertTrue(
        registry.contains("List.of(\"Throughput\"),3,5,1000000000L,1000000000L,\"SECONDS\",1,"));
    for (String annotation :
        List.of(
            "@Fork",
            "@Fork(value=1,jvmArgs={},jvmArgsAppend={},jvmArgsPrepend={})",
            "@Warmup(iterations=-1,time=-1) @Measurement(iterations=-1,time=-1)")) {
      Compilation implicit = compile(work(annotation, "@Benchmark public void run() {}"));
      assertTrue(implicit.success, implicit.diagnostics);
    }
    Compilation tiedNames =
        compile(
            Map.of(
                "example/Work.java",
                work("", "@Benchmark public void run() {}"),
                "example/Other.java",
                "package example; public class Other { @org.openjdk.jmh.annotations.Benchmark"
                    + " public void run() {} }"));
    assertTrue(tiedNames.success, tiedNames.diagnostics);
    Compilation overrides =
        compile(
            work(
                "@Warmup(iterations=-1,time=-1) @Measurement(iterations=-1,time=-1)",
                "@Benchmark @Warmup(iterations=0,time=1) @Measurement(iterations=1,time=1) "
                    + "@BenchmarkMode({Mode.Throughput,Mode.SingleShotTime}) public void run() {}"),
            "-Asmh.observer=dev.elide.smh.runtime.Observer");
    assertFalse(overrides.success); // Interface name is valid, but cannot be constructed.
    assertTrue(overrides.diagnostics.contains("abstract"), overrides.diagnostics);
    assertTrue(
        Files.readString(overrides.generated.resolve("dev/elide/smh/generated/Main.java"))
            .contains("new dev.elide.smh.runtime.Observer()"));
    Compilation invalidObserver =
        compile(work("", "@Benchmark public void run() {}"), "-Asmh.observer=bad-name");
    assertFalse(invalidObserver.success);
    assertTrue(
        invalidObserver.diagnostics.contains("Observer must be a Java class name"),
        invalidObserver.diagnostics);
  }

  @Test
  void unsupportedBenchmarkShapesFailWithUsefulDiagnostics() throws Exception {
    rejects(work("", "@Benchmark void run() {}"), "Benchmark must be public");
    rejects(
        work("abstract", "@Benchmark public abstract void run();"), "Benchmark must be concrete");
    rejects(
        work("", "@Benchmark public void run(int input) {}"),
        "Only @State and JMH Blackhole arguments");
    rejects(
        work("", "@Benchmark public void run(Object input) {}"),
        "Only @State and JMH Blackhole arguments");
    rejects(
        work(
            "",
            "@Benchmark public void run() {} @Benchmark public void run(Extra e) {} "
                + "@State(Scope.Thread) public static class Extra {}"),
        "Overloaded benchmark names");
    rejects(
        work(
            "",
            "@Benchmark public void run(Extra e) {} @State(Scope.Thread) static class Extra {}"),
        "State must be public and concrete");
    rejects(
        work(
            "",
            "@Benchmark public void run(Extra e) {} @State(Scope.Thread) public abstract static"
                + " class Extra {}"),
        "State must be public and concrete");
    rejects(
        work(
            "",
            "@Benchmark public void run(Extra e) {} @State(Scope.Thread) public class Extra {}"),
        "Nested state must be static");
    rejects(
        work("@State(Scope.Group)", "@Benchmark public void run() {}"),
        "Scope.Group is not supported");
  }

  @Test
  void invalidParametersAndFixturesFailBeforeNativeCompilation() throws Exception {
    String method = " @Benchmark public void run() {}";
    for (String field :
        List.of(
            "@Param({\"1\"}) int n;",
            "@Param({\"1\"}) public static int n;",
            "@Param({\"1\"}) public final int n=1;"))
      rejects(work("", field + method), "@Param fields must be public mutable instance fields");
    rejects(work("", "@Param public int n;" + method), "@Param requires explicit values");
    rejects(work("", "@Param public Object n;" + method), "@Param requires explicit values");
    rejects(work("", "@Param({}) public String n;" + method), "Empty parameter values");
    rejects(
        work("", "@Param({\"x\"}) public Object n;" + method),
        "@Param supports primitives, String and enums");
    rejects(
        work("", "@Param({\"x\"}) public Object[] n;" + method),
        "@Param supports primitives, String and enums");
    rejects(
        work(
            "",
            "@Param({\"1\"}) public int n; @Benchmark public void run(Extra e) {}"
                + " @State(Scope.Thread) public static class Extra { @Param({\"2\"}) public int n;"
                + " }"),
        "Parameter names must be unique");
    for (String fixture :
        List.of(
            "@Setup private void setup() {}",
            "@Setup public int setup() { return 1; }",
            "@Setup public void setup(int value) {}",
            "@TearDown public void end(int value) {}"))
      rejects(work("", fixture + method), "Fixtures must be public void methods with no arguments");
  }

  @Test
  void unsupportedAnnotationsAndInvalidCountsAreNeverSilentlyIgnored() throws Exception {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("@Threads(2)", "Only single-thread");
    cases.put("@Fork(2)", "JVM forks/options are unsupported");
    cases.put("@Fork(value=1,warmups=1)", "JVM forks/options are unsupported");
    cases.put("@Fork(value=1,jvm=\"another-jvm\")", "JVM forks/options are unsupported");
    cases.put("@Fork(value=1,jvmArgs={\"-Xmx1g\"})", "JVM forks/options are unsupported");
    cases.put("@Fork(value=1,jvmArgsAppend={\"-Xmx1g\"})", "JVM forks/options are unsupported");
    cases.put("@Fork(value=1,jvmArgsPrepend={\"-Xmx1g\"})", "JVM forks/options are unsupported");
    cases.put("@Warmup(batchSize=2)", "Warmup batchSize is unsupported");
    cases.put("@Measurement(batchSize=2)", "Measurement batchSize is unsupported");
    cases.put("@BenchmarkMode(Mode.SampleTime)", "Unsupported mode");
    cases.put("@Measurement(iterations=0)", "Measurement iterations must be positive");
    cases.put("@Warmup(time=0)", "Iteration duration must be positive");
    cases.put("@Measurement(time=0)", "Iteration duration must be positive");
    cases.put("@OperationsPerInvocation(0)", "OperationsPerInvocation must be positive");
    cases.put("@Timeout(time=1)", "Unsupported JMH annotation");
    for (var entry : cases.entrySet())
      rejects(work("", entry.getKey() + " @Benchmark public void run() {}"), entry.getValue());
  }

  @Test
  void generationIoFailuresProduceDiagnostics() throws Exception {
    Compilation result =
        compile(
            Map.of(
                "example/Work.java",
                work("", "@Benchmark public void run() {}"),
                "dev/elide/smh/generated/Benchmarks.java",
                "package dev.elide.smh.generated; public class Benchmarks {}"));
    assertFalse(result.success);
    assertTrue(result.diagnostics.contains("SMH generation failed:"), result.diagnostics);
  }

  @SuppressWarnings("unchecked")
  @Test
  void everyReturnCarrierUsesAnUnboxedJmhConsumeAndExplicitBlackholesShareOneInstance()
      throws Throwable {
    Map<String, String> returns =
        Map.ofEntries(
            Map.entry("boolean", "true"),
            Map.entry("byte", "(byte)1"),
            Map.entry("short", "(short)2"),
            Map.entry("char", "'a'"),
            Map.entry("int", "3"),
            Map.entry("long", "4L"),
            Map.entry("float", "5f"),
            Map.entry("double", "6d"),
            Map.entry("Object", "new Object()"),
            Map.entry("Object[]", "new Object[]{new Object()}"),
            Map.entry("int[]", "new int[]{1,2}"),
            Map.entry("String", "null"));
    Map<String, String> consumes = new HashMap<>();
    StringBuilder methods = new StringBuilder();
    int index = 0;
    for (var entry : new TreeMap<>(returns).entrySet()) {
      String name = "method" + index++;
      methods
          .append("@Benchmark public ")
          .append(entry.getKey())
          .append(' ')
          .append(name)
          .append("() { return ")
          .append(entry.getValue())
          .append("; }");
      String descriptor =
          switch (entry.getKey()) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "short" -> "S";
            case "char" -> "C";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "Ljava/lang/Object;";
          };
      consumes.put("example.Work." + name, "(" + descriptor + ")V");
    }
    methods.append(
        """
        public static org.openjdk.jmh.infra.Blackhole last;
        @State(Scope.Thread) public static class Extra { public int value = 7; }
        @Benchmark public void explicit(org.openjdk.jmh.infra.Blackhole a, Extra state, org.openjdk.jmh.infra.Blackhole b) {
          if (a == null || a != b || state.value != 7) throw new AssertionError("Bad injected arguments");
          last = a;
          a.tlrMask = 0;
          a.consume(state);
        }
        @Benchmark public void plain() {}
        """);
    Compilation result = compile(work("", methods.toString()));
    assertTrue(result.success, result.diagnostics);
    try (URLClassLoader loader =
        new URLClassLoader(
            new java.net.URL[] {result.classes.toUri().toURL()}, getClass().getClassLoader())) {
      List<BenchmarkSpec> specs =
          (List<BenchmarkSpec>)
              loader.loadClass("dev.elide.smh.generated.Benchmarks").getMethod("all").invoke(null);
      assertEquals(returns.size() + 2, specs.size());
      for (BenchmarkSpec spec : specs) {
        BenchmarkCase adapter = spec.factory().apply(Map.of());
        adapter.invoke();
        assertEquals(spec.name().endsWith("plain") ? "none" : "jmh-1.37-consume", adapter.sink());
        List<String> sinkDescriptors = new ArrayList<>();
        new ClassReader(
                Files.readAllBytes(
                    result.classes.resolve(
                        adapter.getClass().getName().replace('.', '/') + ".class")))
            .accept(
                new ClassVisitor(Opcodes.ASM9) {
                  public MethodVisitor visitMethod(
                      int access,
                      String name,
                      String descriptor,
                      String signature,
                      String[] exceptions) {
                    if (!name.equals("invoke")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                      public void visitMethodInsn(
                          int opcode,
                          String owner,
                          String name,
                          String descriptor,
                          boolean isInterface) {
                        assertFalse(
                            owner.startsWith("java/lang/") && name.equals("valueOf"),
                            "No primitive boxing in the adapter");
                        if (owner.equals("org/openjdk/jmh/infra/Blackhole")
                            && name.equals("consume")) sinkDescriptors.add(descriptor);
                      }

                      public void visitTypeInsn(int opcode, String type) {
                        assertNotEquals(
                            Opcodes.NEW, opcode, "No allocation in the adapter invocation");
                      }
                    };
                  }
                },
                0);
        if (consumes.containsKey(spec.name()))
          assertEquals(List.of(consumes.get(spec.name())), sinkDescriptors);
        else assertTrue(sinkDescriptors.isEmpty());
        adapter.tearDown("Invocation");
        adapter.tearDown("Iteration");
        adapter.tearDown("Trial");
      }
      var captured =
          (org.openjdk.jmh.infra.Blackhole)
              loader.loadClass("example.Work").getField("last").get(null);
      assertNotNull(captured);
      assertNull(captured.obj1, "Generated iteration/trial cleanup clears captured objects");
    }
  }

  @Test
  void generatedTeardownAttemptsEveryStateAndPreservesFailureIdentity() throws Throwable {
    Compilation result =
        compile(
            work(
                "",
                """
                        public static final java.util.List<String> events = new java.util.ArrayList<>();
                        public static final Throwable first = new IllegalStateException("first");
                        public static final Throwable second = new AssertionError("second");
                        public static int failures;
                        public static org.openjdk.jmh.infra.Blackhole captured;
                @Benchmark public void run(Extra extra, org.openjdk.jmh.infra.Blackhole sink) {
                  captured = sink;
                  sink.obj1 = new Object();
                }
                        @TearDown(Level.Trial) public void end() throws Throwable {
                          events.add("owner");
                          if (failures == 2 || failures == 4) throw second;
                          if (failures == 3) throw first;
                        }
                        @State(Scope.Thread) public static class Extra {
                          @TearDown(Level.Trial) public void end() throws Throwable {
                            events.add("extra");
                            if (failures > 0 && failures < 4) throw first;
                          }
                        }
                """));
    assertTrue(result.success, result.diagnostics);
    // Fresh classloaders give each scenario independent exception/suppression state.
    for (int failures = 0; failures <= 4; failures++) {
      try (URLClassLoader loader =
          new URLClassLoader(
              new java.net.URL[] {result.classes.toUri().toURL()}, getClass().getClassLoader())) {
        Class<?> owner = loader.loadClass("example.Work");
        owner.getField("failures").setInt(null, failures);
        @SuppressWarnings("unchecked")
        List<BenchmarkSpec> specs =
            (List<BenchmarkSpec>)
                loader
                    .loadClass("dev.elide.smh.generated.Benchmarks")
                    .getMethod("all")
                    .invoke(null);
        BenchmarkCase adapter = specs.getFirst().factory().apply(Map.of());
        adapter.invoke();
        var captured = (org.openjdk.jmh.infra.Blackhole) owner.getField("captured").get(null);
        adapter.tearDown("Invocation");
        assertNotNull(captured.obj1);
        assertEquals(List.of(), owner.getField("events").get(null));
        if (failures == 0) adapter.tearDown("Trial");
        else {
          Throwable thrown = assertThrows(Throwable.class, () -> adapter.tearDown("Trial"));
          assertSame(owner.getField(failures == 4 ? "second" : "first").get(null), thrown);
          if (failures == 2)
            assertArrayEquals(
                new Throwable[] {(Throwable) owner.getField("second").get(null)},
                thrown.getSuppressed());
          else assertEquals(0, thrown.getSuppressed().length);
        }
        assertEquals(List.of("extra", "owner"), owner.getField("events").get(null));
        assertNull(captured.obj1, "Blackhole references must clear even if several teardowns fail");
      }
    }
  }

  @Test
  void failedSetupUnwindsOnlyCompletedStatesAtEveryLifecycleLevel() throws Throwable {
    for (String level : List.of("Trial", "Iteration", "Invocation")) {
      Compilation result =
          compile(
              work(
                  "",
                  """
                  public static final java.util.List<String> events = new java.util.ArrayList<>();
                  public static final Throwable primary = new IllegalStateException("setup");
                  public static int failAt, cleanupMode;
                  public static void enter(int state) throws Throwable {
                    events.add("enter" + state);
                    if (failAt == state) throw primary;
                  }
                  public static void leave(int state) throws Throwable {
                    events.add("leave" + state);
                    if (cleanupMode == 1) throw new AssertionError("cleanup" + state);
                    if (cleanupMode == 2) throw primary;
                    if (cleanupMode == 3 || cleanupMode == 4) {
                      if (state == cleanupMode - 3) throw new AssertionError("cleanup" + state);
                      throw primary;
                    }
                  }
                  @Benchmark public void run(A a, B b) {}
                  @Setup(Level.LEVEL) public void setup() throws Throwable { enter(0); }
                  @TearDown(Level.LEVEL) public void end() throws Throwable { leave(0); }
                  @State(Scope.Thread) public static class A {
                    @Setup(Level.LEVEL) public void setup() throws Throwable { enter(1); }
                    @TearDown(Level.LEVEL) public void end() throws Throwable { leave(1); }
                  }
                  @State(Scope.Thread) public static class B {
                    @Setup(Level.LEVEL) public void setup() throws Throwable { enter(2); }
                    @TearDown(Level.LEVEL) public void end() throws Throwable { leave(2); }
                  }
                  """
                      .replace("LEVEL", level)));
      assertTrue(result.success, result.diagnostics);
      for (int failAt = 0; failAt < 3; failAt++) {
        for (int cleanupMode = 0; cleanupMode < 5; cleanupMode++) {
          try (URLClassLoader loader =
              new URLClassLoader(
                  new java.net.URL[] {result.classes.toUri().toURL()},
                  getClass().getClassLoader())) {
            Class<?> owner = loader.loadClass("example.Work");
            owner.getField("failAt").setInt(null, failAt);
            owner.getField("cleanupMode").setInt(null, cleanupMode);
            @SuppressWarnings("unchecked")
            List<BenchmarkSpec> specs =
                (List<BenchmarkSpec>)
                    loader
                        .loadClass("dev.elide.smh.generated.Benchmarks")
                        .getMethod("all")
                        .invoke(null);
            BenchmarkCase adapter = specs.getFirst().factory().apply(Map.of());
            Throwable thrown = assertThrows(Throwable.class, () -> adapter.setup(level));
            assertSame(owner.getField("primary").get(null), thrown);
            List<String> expected = new ArrayList<>();
            for (int state = 0; state <= failAt; state++) expected.add("enter" + state);
            for (int state = failAt - 1; state >= 0; state--) expected.add("leave" + state);
            assertEquals(expected, owner.getField("events").get(null));
            List<String> suppressed = new ArrayList<>();
            for (int state = failAt - 1; state >= 0; state--)
              if (cleanupMode == 1 || (cleanupMode >= 3 && state == cleanupMode - 3))
                suppressed.add("cleanup" + state);
            assertEquals(
                suppressed,
                Arrays.stream(thrown.getSuppressed()).map(Throwable::getMessage).toList());
            for (Throwable cleanup : thrown.getSuppressed())
              assertEquals(0, cleanup.getSuppressed().length);
          }
        }
      }
    }
  }

  @Test
  void allSixMynaTargetsCompileAndExposeTheDescriptorFingerprint() throws Exception {
    for (Platform.OperatingSystem os : Platform.OperatingSystem.values())
      for (Platform.Architecture architecture : Platform.Architecture.values()) {
        String target = new Platform(os, architecture).targetTriple();
        assertEquals(new Platform(os, architecture), Platform.fromTargetTriple(target));
        Compilation result =
            compile(work("", "@Benchmark public void run() {}"), "-Asmh.target=" + target);
        assertTrue(result.success, result.diagnostics);
        String expected = Files.readString(Path.of("abi/generated", target, "seam.abi")).strip();
        byte[] json = Files.readAllBytes(Path.of("abi/generated", target, "seam.json"));
        assertEquals(
            expected,
            java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json)));
        try (URLClassLoader loader =
            new URLClassLoader(
                new java.net.URL[] {result.classes.toUri().toURL()}, getClass().getClassLoader())) {
          assertEquals(
              expected,
              loader
                  .loadClass("dev.elide.smh.generated.nativeapi.SmhNative")
                  .getField("ABI_FINGERPRINT")
                  .get(null));
          var tag = loader.loadClass("dev.elide.smh.generated.Main").getMethod("abiTag", int.class);
          var fingerprint = new java.math.BigInteger(expected, 16);
          for (int index = 0; index < 4; index++)
            assertEquals(
                fingerprint.shiftRight((3 - index) * 64).longValue(), tag.invoke(null, index));
          assertEquals(0L, tag.invoke(null, -1));
          assertEquals(0L, tag.invoke(null, 4));
        }
      }
    assertThrows(IllegalArgumentException.class, () -> Platform.fromTargetTriple("../unsupported"));
    Compilation bad =
        compile(work("", "@Benchmark public void run() {}"), "-Asmh.target=unsupported");
    assertFalse(bad.success);
    assertTrue(bad.diagnostics.contains("Unsupported ABI target"), bad.diagnostics);
  }

  @Test
  void runtimeLoadedHostExecutesTheGeneratedSuiteAndRestoresContextLoader() throws Throwable {
    Compilation compiled =
        compile(
            work(
                "@State(Scope.Thread)",
                "private int count; @Benchmark public int run() { return ++count; }"));
    assertTrue(compiled.success, compiled.diagnostics);
    Path result = directory.resolve("host-results.json");
    com.google.gson.JsonObject settings = new com.google.gson.JsonObject();
    settings.addProperty("output", result.toString());
    settings.addProperty("warmupIterations", 0);
    settings.addProperty("measurementIterations", 1);
    settings.addProperty("measurementNanos", 10000);
    Path config = Files.writeString(directory.resolve("host.json"), settings.toString());
    ClassLoader before = Thread.currentThread().getContextClassLoader();
    BenchmarkHost.main(new String[] {config.toString(), compiled.classes.toString()});
    assertSame(before, Thread.currentThread().getContextClassLoader());
    var rows = com.google.gson.JsonParser.parseString(Files.readString(result)).getAsJsonArray();
    assertEquals(1, rows.size());
    assertEquals("example.Work.run", rows.get(0).getAsJsonObject().get("benchmark").getAsString());
    assertThrows(IllegalArgumentException.class, () -> BenchmarkHost.main(new String[0]));
    assertThrows(
        ClassNotFoundException.class,
        () ->
            BenchmarkHost.main(
                new String[] {config.toString(), directory.resolve("absent").toString()}));
    settings.addProperty("include", "no-match");
    Files.writeString(config, settings.toString());
    assertThrows(
        IllegalArgumentException.class,
        () -> BenchmarkHost.main(new String[] {config.toString(), compiled.classes.toString()}));
    assertSame(before, Thread.currentThread().getContextClassLoader());
  }
}
