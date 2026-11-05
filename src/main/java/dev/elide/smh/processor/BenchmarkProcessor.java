// SPDX-FileCopyrightText: 2026 SMH contributors
// SPDX-License-Identifier: Apache-2.0 OR MIT
package dev.elide.smh.processor;

import dev.elide.smh.runtime.Platform;
import java.io.*;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import org.openjdk.jmh.annotations.*;

/** Builds closed-world benchmark adapters from the real JMH annotations. */
@SupportedAnnotationTypes("org.openjdk.jmh.annotations.*")
@SupportedOptions({"smh.observer", "smh.target"})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class BenchmarkProcessor extends AbstractProcessor {
  private boolean generated;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    if (generated || round.processingOver()) return false;
    List<ExecutableElement> methods =
        round.getElementsAnnotatedWith(Benchmark.class).stream()
            .map(e -> (ExecutableElement) e)
            .sorted(
                Comparator.comparing((ExecutableElement e) -> e.toString())
                    .thenComparing(e -> e.getEnclosingElement().toString()))
            .toList();
    if (methods.isEmpty()) return false;
    generated = true;
    try {
      StringBuilder source =
          new StringBuilder(
              "package dev.elide.smh.generated;\n"
                  + "import java.util.*;\n"
                  + "import dev.elide.smh.runtime.*;\n"
                  + "public final class Benchmarks implements BenchmarkSuite {\n"
                  + "public static List<BenchmarkSpec> all() { return List.of(\n");
      List<String> descriptors = new ArrayList<>();
      List<String> adapters = new ArrayList<>();
      Set<String> names = new HashSet<>();
      int id = 0;
      for (ExecutableElement method : methods) {
        TypeElement owner = (TypeElement) method.getEnclosingElement();
        String name = owner.getQualifiedName() + "." + method.getSimpleName();
        require(names.add(name), method, "Overloaded benchmark names are unsupported");
        validateOptions(owner);
        validateOptions(method);
        require(
            method.getModifiers().contains(Modifier.PUBLIC), method, "Benchmark must be public");
        require(
            !method.getModifiers().contains(Modifier.ABSTRACT),
            method,
            "Benchmark must be concrete");
        Map<String, TypeElement> states = new LinkedHashMap<>();
        states.put(owner.getQualifiedName().toString(), owner);
        List<String> args = new ArrayList<>();
        boolean usesBlackhole = method.getReturnType().getKind() != TypeKind.VOID;
        for (VariableElement arg : method.getParameters()) {
          if (arg.asType().toString().equals("org.openjdk.jmh.infra.Blackhole")) {
            usesBlackhole = true;
            args.add("blackhole");
            continue;
          }
          Element type = processingEnv.getTypeUtils().asElement(arg.asType());
          require(
              type instanceof TypeElement && type.getAnnotation(State.class) != null,
              arg,
              "Only @State and JMH Blackhole arguments are supported; control objects are"
                  + " deferred");
          states.putIfAbsent(
              ((TypeElement) type).getQualifiedName().toString(), (TypeElement) type);
          args.add("s" + new ArrayList<>(states.keySet()).indexOf(type.toString()));
        }
        Map<String, List<String>> params = new TreeMap<>();
        StringBuilder adapter =
            new StringBuilder("static final class B" + id + " implements BenchmarkCase {\n");
        if (usesBlackhole)
          adapter
              .append(
                  "private final org.openjdk.jmh.infra.Blackhole blackhole ="
                      + " Blackholes.create();\n")
              .append("public String sink() { return \"jmh-1.37-consume\"; }\n");
        List<TypeElement> stateTypes = new ArrayList<>(states.values());
        for (int s = 0; s < stateTypes.size(); s++) {
          TypeElement state = stateTypes.get(s);
          validateOptions(state);
          require(
              state.getModifiers().contains(Modifier.PUBLIC)
                  && !state.getModifiers().contains(Modifier.ABSTRACT),
              state,
              "State must be public and concrete");
          require(
              state.getNestingKind() == NestingKind.TOP_LEVEL
                  || state.getModifiers().contains(Modifier.STATIC),
              state,
              "Nested state must be static");
          State annotation = state.getAnnotation(State.class);
          require(
              annotation == null || annotation.value() != Scope.Group,
              state,
              "Scope.Group is not supported");
          adapter
              .append(state.getQualifiedName())
              .append(" s")
              .append(s)
              .append(" = new ")
              .append(state.getQualifiedName())
              .append("();\n");
        }
        adapter.append("B").append(id).append("(Map<String,String> p) {\n");
        for (int s = 0; s < stateTypes.size(); s++)
          for (Element member : processingEnv.getElementUtils().getAllMembers(stateTypes.get(s))) {
            Param param = member.getAnnotation(Param.class);
            if (param == null) continue;
            require(member instanceof VariableElement, member, "@Param must be a field");
            VariableElement field = (VariableElement) member;
            require(
                field.getModifiers().contains(Modifier.PUBLIC)
                    && !field.getModifiers().contains(Modifier.STATIC)
                    && !field.getModifiers().contains(Modifier.FINAL),
                field,
                "@Param fields must be public mutable instance fields");
            String key = field.getSimpleName().toString();
            require(
                !params.containsKey(key),
                field,
                "Parameter names must be unique across states: " + key);
            List<String> values = List.of(param.value());
            Element type = processingEnv.getTypeUtils().asElement(field.asType());
            if (values.equals(List.of(Param.BLANK_ARGS))) {
              require(
                  type != null && type.getKind() == ElementKind.ENUM,
                  field,
                  "@Param requires explicit values unless the field is an enum");
              values =
                  type.getEnclosedElements().stream()
                      .filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                      .map(e -> e.getSimpleName().toString())
                      .toList();
            }
            require(!values.isEmpty(), field, "Empty parameter values");
            params.put(key, values);
            String value = "p.get(" + quote(key) + ")";
            String conversion =
                switch (field.asType().getKind()) {
                  case BOOLEAN -> "Values.bool(" + value + ")";
                  case BYTE -> "Byte.parseByte(" + value + ")";
                  case SHORT -> "Short.parseShort(" + value + ")";
                  case INT -> "Integer.parseInt(" + value + ")";
                  case LONG -> "Long.parseLong(" + value + ")";
                  case FLOAT -> "Float.parseFloat(" + value + ")";
                  case DOUBLE -> "Double.parseDouble(" + value + ")";
                  case CHAR -> "Values.character(" + value + ")";
                  default -> {
                    if (field.asType().toString().equals("java.lang.String")) yield value;
                    require(
                        type != null && type.getKind() == ElementKind.ENUM,
                        field,
                        "@Param supports primitives, String and enums");
                    yield field.asType() + ".valueOf(" + value + ")";
                  }
                };
            adapter
                .append("s")
                .append(s)
                .append('.')
                .append(key)
                .append(" = ")
                .append(conversion)
                .append(";\n");
          }
        adapter.append("}\npublic void invoke() throws Throwable { ");
        if (method.getReturnType().getKind() != TypeKind.VOID) adapter.append("blackhole.consume(");
        adapter
            .append("s0.")
            .append(method.getSimpleName())
            .append('(')
            .append(String.join(",", args))
            .append(')');
        if (method.getReturnType().getKind() != TypeKind.VOID) adapter.append(')');
        adapter.append("; }\n");
        boolean invocationFixtures = false;
        for (boolean setup : List.of(true, false)) {
          if (setup)
            adapter.append(
                "public void setup(String level) throws Throwable {\n"
                    + "int completed = 0; try {\n");
          else
            adapter
                .append(
                    "public void tearDown(String level) throws Throwable {" + " tearDown(level, ")
                .append(stateTypes.size())
                .append(
                    ", null); }\n"
                        + "private void tearDown(String level, int completed, Throwable failure)"
                        + " throws Throwable {\n");
          List<String> calls = new ArrayList<>();
          if (!setup && usesBlackhole) adapter.append("try {\n");
          for (int s = 0; s < stateTypes.size(); s++) {
            List<ExecutableElement> fixtures = new ArrayList<>();
            for (Element member : processingEnv.getElementUtils().getAllMembers(stateTypes.get(s)))
              if ((setup ? member.getAnnotation(Setup.class) : member.getAnnotation(TearDown.class))
                  != null) fixtures.add((ExecutableElement) member);
            // Supertypes before subtypes for setup, reversed for teardown.
            fixtures.sort(
                Comparator.comparingInt(e -> depth((TypeElement) e.getEnclosingElement())));
            for (ExecutableElement fixture : fixtures) {
              require(
                  fixture.getModifiers().contains(Modifier.PUBLIC)
                      && fixture.getParameters().isEmpty()
                      && fixture.getReturnType().getKind() == TypeKind.VOID,
                  fixture,
                  "Fixtures must be public void methods with no arguments");
              String level =
                  setup
                      ? fixture.getAnnotation(Setup.class).value().name()
                      : fixture.getAnnotation(TearDown.class).value().name();
              if (level.equals("Invocation")) invocationFixtures = true;
              calls.add(
                  "if ("
                      + (setup ? "" : "completed > " + s + " && ")
                      + "level.equals("
                      + quote(level)
                      + ")) s"
                      + s
                      + "."
                      + fixture.getSimpleName()
                      + "();\n");
            }
            if (setup) calls.add("completed = " + (s + 1) + ";\n");
          }
          if (!setup) Collections.reverse(calls);
          if (setup) {
            calls.forEach(adapter::append);
            adapter.append(
                "} catch (Throwable primary) {\n"
                    + "tearDown(level, completed, primary);\n"
                    + "throw primary; }\n");
          } else {
            for (String call : calls)
              adapter
                  .append("try { ")
                  .append(call)
                  .append(
                      "} catch (Throwable t) { if (failure == null) failure = t;"
                          + " else if (failure != t) failure.addSuppressed(t); }\n");
            adapter.append("if (failure != null) throw failure;\n");
          }
          if (!setup && usesBlackhole)
            adapter.append(
                "} finally { if (level.equals(\"Iteration\") || level.equals(\"Trial\"))"
                    + " Blackholes.clear(blackhole); }\n");
          adapter.append("}\n");
        }
        if (invocationFixtures)
          adapter.append("public boolean hasInvocationFixtures() { return true; }\n");
        adapter.append("}\n");
        adapters.add(adapter.toString());
        BenchmarkMode mode = option(method, owner, BenchmarkMode.class);
        List<String> modes =
            mode == null
                ? List.of("Throughput")
                : Arrays.stream(mode.value()).map(Enum::name).toList();
        for (String m : modes)
          require(
              Set.of("Throughput", "AverageTime", "SingleShotTime").contains(m),
              method,
              "Unsupported mode: " + m);
        Warmup warmup = option(method, owner, Warmup.class);
        Measurement measurement = option(method, owner, Measurement.class);
        OutputTimeUnit timeUnit = option(method, owner, OutputTimeUnit.class);
        OperationsPerInvocation operations = option(method, owner, OperationsPerInvocation.class);
        int wi = warmup == null || warmup.iterations() < 0 ? 3 : warmup.iterations();
        int mi = measurement == null || measurement.iterations() < 0 ? 5 : measurement.iterations();
        require(mi > 0, method, "Measurement iterations must be positive");
        long wn =
            warmup == null || warmup.time() < 0
                ? 1_000_000_000L
                : warmup.timeUnit().toNanos(warmup.time());
        long mn =
            measurement == null || measurement.time() < 0
                ? 1_000_000_000L
                : measurement.timeUnit().toNanos(measurement.time());
        require(wn > 0 && mn > 0, method, "Iteration duration must be positive");
        require(
            operations == null || operations.value() > 0,
            method,
            "OperationsPerInvocation must be positive");
        List<String> dims = new ArrayList<>();
        params.forEach(
            (k, v) ->
                dims.add(
                    "Map.entry("
                        + quote(k)
                        + ",List.of("
                        + v.stream()
                            .map(BenchmarkProcessor::quote)
                            .collect(java.util.stream.Collectors.joining(","))
                        + "))"));
        descriptors.add(
            "new BenchmarkSpec("
                + quote(name)
                + ",Map.ofEntries("
                + String.join(",", dims)
                + "),List.of("
                + modes.stream()
                    .map(BenchmarkProcessor::quote)
                    .collect(java.util.stream.Collectors.joining(","))
                + "),"
                + wi
                + ","
                + mi
                + ","
                + wn
                + "L,"
                + mn
                + "L,"
                + quote(timeUnit == null ? "SECONDS" : timeUnit.value().name())
                + ","
                + (operations == null ? 1 : operations.value())
                + ",B"
                + id
                + "::new)");
        id++;
      }
      source.append(String.join(",\n", descriptors)).append("); }\n");
      adapters.forEach(source::append);

      String observer = processingEnv.getOptions().get("smh.observer");
      if (observer != null)
        require(
            observer.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"),
            methods.getFirst(),
            "Observer must be a Java class name");
      source.append("public List<BenchmarkSpec> benchmarks() { return all(); }\n");
      source
          .append("public dev.elide.smh.runtime.Observer observer() { return ")
          .append(
              observer == null ? "dev.elide.smh.runtime.Observer.NONE" : "new " + observer + "()")
          .append("; }\n}\n");
      write("dev.elide.smh.generated.Benchmarks", source.toString());
      write(
          "dev.elide.smh.generated.Main",
          """
          package dev.elide.smh.generated;
          import dev.elide.smh.runtime.*;
          import org.graalvm.nativeimage.c.type.*;
          public final class Main {
            public static void main(String[] args) throws Throwable {
              if (args.length > 1) throw new IllegalArgumentException("usage: benchmark [RUN_CONFIG.json]");
              execute(args.length == 0 ? "" : args[0]);
            }
            private static void execute(String path) throws Throwable {
              System.out.println(Runner.run(Benchmarks.all(), RunConfig.read(path), %s));
            }
            public static long abiTag(int index) {
              if (index < 0 || index > 3) return 0;
              String fingerprint = dev.elide.smh.generated.nativeapi.SmhNative.ABI_FINGERPRINT;
              return Long.parseUnsignedLong(fingerprint.substring(index * 16, (index + 1) * 16), 16);
            }
            public static int nativeRun(CCharPointer path) {
              try { execute(CTypeConversion.toJavaString(path)); return 0; }
              catch (Throwable e) { e.printStackTrace(System.err); return 1; }
            }
          }
          """
              .formatted(observer == null ? "Observer.NONE" : "new " + observer + "()"));
      String target = processingEnv.getOptions().get("smh.target");
      Platform platform = target == null ? Platform.current() : Platform.fromTargetTriple(target);
      write(
          "dev.elide.smh.generated.nativeapi.SmhNative",
          AbiBindings.source(platform.targetTriple()));
    } catch (InvalidBenchmark ignored) {
      // A source diagnostic has already identified the unsupported feature.
    } catch (Exception e) {
      processingEnv
          .getMessager()
          .printMessage(Diagnostic.Kind.ERROR, "SMH generation failed: " + e);
    }
    return false;
  }

  private int depth(TypeElement type) {
    int n = 0;
    while (type.getSuperclass().getKind() != TypeKind.NONE) {
      n++;
      type = (TypeElement) processingEnv.getTypeUtils().asElement(type.getSuperclass());
    }
    return n;
  }

  private void validateOptions(Element e) {
    for (var a : e.getAnnotationMirrors()) {
      String name = a.getAnnotationType().toString();
      if (name.startsWith("org.openjdk.jmh.annotations."))
        require(
            Set.of(
                    "Benchmark",
                    "State",
                    "BenchmarkMode",
                    "Warmup",
                    "Measurement",
                    "OutputTimeUnit",
                    "OperationsPerInvocation",
                    "Threads",
                    "Fork")
                .contains(a.getAnnotationType().asElement().getSimpleName().toString()),
            e,
            "Unsupported JMH annotation: " + name);
    }
    Threads t = e.getAnnotation(Threads.class);
    require(
        t == null || t.value() == 1,
        e,
        "Only single-thread AOT benchmarks are supported currently");
    Fork f = e.getAnnotation(Fork.class);
    require(
        f == null
            || ((f.value() == 1 || f.value() == Fork.BLANK_FORKS)
                && f.warmups() <= 0
                && f.jvm().equals(Fork.BLANK_ARGS)
                && noJvmArgs(f.jvmArgs())
                && noJvmArgs(f.jvmArgsAppend())
                && noJvmArgs(f.jvmArgsPrepend())),
        e,
        "JVM forks/options are unsupported; use independent native process runs");
    Warmup w = e.getAnnotation(Warmup.class);
    Measurement m = e.getAnnotation(Measurement.class);
    require(w == null || w.batchSize() <= 1, e, "Warmup batchSize is unsupported");
    require(m == null || m.batchSize() <= 1, e, "Measurement batchSize is unsupported");
  }

  private static boolean noJvmArgs(String[] args) {
    return args.length == 0 || Arrays.equals(args, new String[] {Fork.BLANK_ARGS});
  }

  private <A extends java.lang.annotation.Annotation> A option(
      Element method, Element owner, Class<A> type) {
    A a = method.getAnnotation(type);
    return a == null ? owner.getAnnotation(type) : a;
  }

  private void require(boolean valid, Element e, String message) {
    if (!valid) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
      throw new InvalidBenchmark();
    }
  }

  private static final class InvalidBenchmark extends RuntimeException {}

  private void write(String name, String source) throws IOException {
    try (Writer w = processingEnv.getFiler().createSourceFile(name).openWriter()) {
      w.write(source);
    }
  }

  private static String quote(String s) {
    return "\""
        + s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        + "\"";
  }
}
