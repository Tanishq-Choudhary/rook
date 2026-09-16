package dev.rook.eval.build;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

public final class JarBuilder {

    private JarBuilder() {}

    public static Path fromClasses(Path classes, Path jar) {
        try {
            Files.createDirectories(jar.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(jar);
                    JarOutputStream jarOut = new JarOutputStream(out);
                    Stream<Path> walk = Files.walk(classes)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    JarEntry entry =
                            new JarEntry(
                                    classes.relativize(file)
                                            .toString()
                                            .replace(File.separatorChar, '/'));
                    entry.setTime(0);
                    jarOut.putNextEntry(entry);
                    jarOut.write(Files.readAllBytes(file));
                    jarOut.closeEntry();
                }
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
