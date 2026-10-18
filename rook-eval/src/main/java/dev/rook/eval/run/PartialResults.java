package dev.rook.eval.run;

import dev.rook.core.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public final class PartialResults {

    private final Path root;

    public PartialResults(Path root) {
        this.root = root;
    }

    public Optional<FixtureResult> load(String strategy, String fixtureId) {
        Path file = file(strategy, fixtureId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.read(Files.readString(file), FixtureResult.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public void save(FixtureResult result) {
        Path file = file(result.strategy(), result.fixtureId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(result));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void clear() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String strategy, String fixtureId) {
        return root.resolve(strategy).resolve(fixtureId + ".json");
    }
}
