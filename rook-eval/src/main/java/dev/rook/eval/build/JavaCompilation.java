package dev.rook.eval.build;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class JavaCompilation {

    public record Result(boolean success, List<String> errors, Path classes) {

        public Result {
            errors = List.copyOf(errors);
        }
    }

    private JavaCompilation() {}

    public static Result compile(List<Path> sourceRoots, List<Path> classpath, Path output) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("a JDK is required to compile fixtures");
        }
        List<Path> sources = new ArrayList<>();
        for (Path root : sourceRoots) {
            sources.addAll(javaFiles(root));
        }
        try {
            Files.createDirectories(output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (sources.isEmpty()) {
            return new Result(true, List.of(), output);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>();
            options.add("-d");
            options.add(output.toString());
            options.add("-proc:none");
            options.add("-nowarn");
            options.add("--release");
            options.add("21");
            if (!classpath.isEmpty()) {
                options.add("-classpath");
                options.add(
                        classpath.stream()
                                .map(Path::toString)
                                .collect(Collectors.joining(File.pathSeparator)));
            }
            boolean ok =
                    compiler.getTask(
                                    null,
                                    files,
                                    diagnostics,
                                    options,
                                    null,
                                    files.getJavaFileObjectsFromPaths(sources))
                            .call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(describe(diagnostic));
                }
            }
            return new Result(ok, errors, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String describe(Diagnostic<? extends JavaFileObject> diagnostic) {
        String file =
                diagnostic.getSource() == null
                        ? "?"
                        : Path.of(diagnostic.getSource().toUri()).getFileName().toString();
        return file + ":" + diagnostic.getLineNumber() + " " + diagnostic.getMessage(null);
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
