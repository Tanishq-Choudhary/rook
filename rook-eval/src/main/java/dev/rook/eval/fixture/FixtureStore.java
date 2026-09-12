package dev.rook.eval.fixture;

import dev.rook.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class FixtureStore {

    private FixtureStore() {}

    public static Fixture load(Path directory) {
        Path specFile = directory.resolve(Fixture.SPEC_FILE);
        if (!Files.isRegularFile(specFile)) {
            throw new FixtureException("missing " + Fixture.SPEC_FILE + " in " + directory);
        }
        FixtureSpec spec;
        try {
            spec = Json.read(Files.readString(specFile, StandardCharsets.UTF_8), FixtureSpec.class);
        } catch (IOException | RuntimeException e) {
            throw new FixtureException("cannot read " + specFile, e);
        }
        Fixture fixture = new Fixture(spec, directory);
        String folder = directory.getFileName().toString();
        if (!folder.equals(spec.id())) {
            throw new FixtureException(
                    "folder " + folder + " does not match fixture id " + spec.id());
        }
        List<String> problems = check(fixture);
        if (!problems.isEmpty()) {
            throw new FixtureException(spec.id() + " is malformed: " + String.join("; ", problems));
        }
        return fixture;
    }

    public static List<Fixture> loadAll(Path root) {
        if (!Files.isDirectory(root)) {
            throw new FixtureException("fixture root is not a directory: " + root);
        }
        List<Fixture> fixtures = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                Fixture fixture = load(child);
                if (!ids.add(fixture.id())) {
                    throw new FixtureException("duplicate fixture id " + fixture.id());
                }
                fixtures.add(fixture);
            }
        } catch (IOException e) {
            throw new FixtureException("cannot list " + root, e);
        }
        return List.copyOf(fixtures);
    }

    public static void write(Fixture fixture) {
        try {
            Files.createDirectories(fixture.directory());
            Files.writeString(
                    fixture.directory().resolve(Fixture.SPEC_FILE),
                    Json.writePretty(fixture.spec()) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FixtureException("cannot write " + fixture.directory(), e);
        }
    }

    static List<String> check(Fixture fixture) {
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(fixture.repo())) {
            problems.add("repo/ is missing");
        } else if (!Files.isRegularFile(fixture.repo().resolve("pom.xml"))) {
            problems.add("repo/pom.xml is missing");
        }
        if (Fixture.readTree(fixture.sdkBefore()).isEmpty()) {
            problems.add("sdk/before has no sources");
        }
        if (Fixture.readTree(fixture.sdkAfter()).isEmpty()) {
            problems.add("sdk/after has no sources");
        }
        if (Fixture.readTree(fixture.verify()).isEmpty()) {
            problems.add("verify/ has no independent tests");
        }
        if (!fixture.spec().noop() && Fixture.readTree(fixture.gold()).isEmpty()) {
            problems.add("gold/ is empty for a fixture that needs a change");
        }
        for (FixtureSpec.ExpectedLocation location : fixture.spec().expected()) {
            Path file = fixture.repo().resolve(location.file());
            if (!Files.isRegularFile(file)) {
                problems.add("expected file does not exist: " + location.file());
            } else if (location.line() < 1 || lineCount(file) < location.line()) {
                problems.add("expected line is outside the file: " + location.key());
            }
        }
        for (String path : fixture.spec().untouchable()) {
            if (!Files.isRegularFile(fixture.repo().resolve(path))) {
                problems.add("untouchable file does not exist: " + path);
            }
        }
        return problems;
    }

    private static long lineCount(Path file) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.count();
        } catch (IOException e) {
            throw new FixtureException("cannot read " + file, e);
        }
    }
}
