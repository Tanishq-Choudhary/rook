package dev.rook.eval.fixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public record Fixture(FixtureSpec spec, Path directory) {

    public static final String SPEC_FILE = "fixture.json";

    public String id() {
        return spec.id();
    }

    public Path repo() {
        return directory.resolve("repo");
    }

    public Path sdkBefore() {
        return directory.resolve("sdk").resolve("before");
    }

    public Path sdkAfter() {
        return directory.resolve("sdk").resolve("after");
    }

    public Path verify() {
        return directory.resolve("verify");
    }

    public Path gold() {
        return directory.resolve("gold");
    }

    public Map<String, String> goldFiles() {
        return readTree(gold());
    }

    public Map<String, String> sdkAfterFiles() {
        return readTree(sdkAfter());
    }

    public Map<String, String> sdkBeforeFiles() {
        return readTree(sdkBefore());
    }

    public static Map<String, String> readTree(Path root) {
        Map<String, String> files = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                files.put(relative, Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }
}
