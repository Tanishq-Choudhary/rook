package dev.rook.eval.verify;

import dev.rook.eval.fixture.Fixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class FixtureProject {

    private static final String SOURCES = "src/main/java/";
    private static final String TESTS = "src/test/java/";

    private FixtureProject() {}

    public static Path assemble(Fixture fixture, Path repoRoot, SdkVersion sdk, Path target) {
        delete(target);
        copy(repoRoot, target);
        Map<String, String> sources =
                sdk == SdkVersion.BEFORE ? fixture.sdkBeforeFiles() : fixture.sdkAfterFiles();
        write(target, prefixed(SOURCES, sources));
        write(target, Fixture.readTree(fixture.verify()));
        return target;
    }

    public static Path withGold(Fixture fixture, Path target) {
        delete(target);
        copy(fixture.repo(), target);
        write(target, fixture.goldFiles());
        return target;
    }

    public static Set<String> independentClasses(Fixture fixture) {
        Set<String> classes = new TreeSet<>();
        for (String path : Fixture.readTree(fixture.verify()).keySet()) {
            if (path.startsWith(TESTS) && path.endsWith(".java")) {
                classes.add(
                        path.substring(TESTS.length(), path.length() - ".java".length())
                                .replace('/', '.'));
            }
        }
        return classes;
    }

    private static Map<String, String> prefixed(String prefix, Map<String, String> files) {
        Map<String, String> result = new TreeMap<>();
        files.forEach((path, content) -> result.put(prefix + path, content));
        return result;
    }

    private static void write(Path root, Map<String, String> files) {
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = root.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copy(Path from, Path to) {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
