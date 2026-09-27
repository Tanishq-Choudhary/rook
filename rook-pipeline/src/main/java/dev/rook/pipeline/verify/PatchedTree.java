package dev.rook.pipeline.verify;

import dev.rook.core.error.RookException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

public final class PatchedTree {

    private PatchedTree() {}

    public static Path create(
            Path baseline, Path target, Map<String, String> files, Collection<String> deletions) {
        try (Stream<Path> walk = Files.walk(baseline)) {
            for (Path source : walk.toList()) {
                Path relative = baseline.relativize(source);
                if (relative.toString().isEmpty() || startsWithGit(relative)) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination);
                }
            }
            Files.createDirectories(target);
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path destination = inside(target, file.getKey());
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, file.getValue(), StandardCharsets.UTF_8);
            }
            for (String path : deletions) {
                Files.deleteIfExists(inside(target, path));
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean startsWithGit(Path relative) {
        return relative.getName(0).toString().equals(".git");
    }

    private static Path inside(Path root, String relative) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path resolved = absoluteRoot.resolve(relative).normalize();
        if (!resolved.startsWith(absoluteRoot) || resolved.equals(absoluteRoot)) {
            throw RookException.invalid("the patch path leaves the repository: " + relative);
        }
        if (resolved.getName(absoluteRoot.getNameCount()).toString().equals(".git")) {
            throw RookException.invalid("the patch path is inside the git directory");
        }
        return resolved;
    }
}
