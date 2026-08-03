package dev.rook.changes.jar;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class TestJars {

    private TestJars() {}

    public static Path build(Path directory, String name, Map<String, String> sources) {
        try {
            Path classes = Files.createDirectories(directory.resolve(name + "-classes"));
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                    compiler.getStandardFileManager(null, null, null)) {
                List<JavaFileObject> units = new ArrayList<>();
                for (Map.Entry<String, String> source : sources.entrySet()) {
                    units.add(new Source(source.getKey(), source.getValue()));
                }
                boolean ok =
                        compiler.getTask(
                                        null,
                                        files,
                                        null,
                                        List.of("-d", classes.toString(), "-proc:none", "-nowarn"),
                                        null,
                                        units)
                                .call();
                if (!ok) {
                    throw new IllegalStateException("fixture sources do not compile");
                }
            }
            Path jar = directory.resolve(name + ".jar");
            try (OutputStream out = Files.newOutputStream(jar);
                    JarOutputStream jarOut = new JarOutputStream(out);
                    Stream<Path> walk = Files.walk(classes)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName = classes.relativize(file).toString().replace('\\', '/');
                    JarEntry entry = new JarEntry(entryName);
                    entry.setTime(0);
                    jarOut.putNextEntry(entry);
                    jarOut.write(Files.readAllBytes(file));
                    jarOut.closeEntry();
                }
            }
            return jar;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private final String code;

        Source(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
