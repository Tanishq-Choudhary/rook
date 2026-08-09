package dev.rook.codeintel.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class LocalMavenRepository {

    private final Path root;

    public LocalMavenRepository(Path root) {
        this.root = root;
    }

    public static LocalMavenRepository userDefault() {
        return new LocalMavenRepository(
                Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    public Optional<Path> jar(Dependency dependency) {
        if (dependency.version() == null) {
            return Optional.empty();
        }
        Path jar =
                root.resolve(dependency.groupId().replace('.', '/'))
                        .resolve(dependency.artifactId())
                        .resolve(dependency.version())
                        .resolve(dependency.artifactId() + "-" + dependency.version() + ".jar");
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    public List<Path> jars(List<Dependency> dependencies) {
        return dependencies.stream().map(this::jar).flatMap(Optional::stream).distinct().toList();
    }
}
