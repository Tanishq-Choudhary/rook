package dev.rook.codeintel;

import dev.rook.core.error.RookException;
import dev.rook.core.security.SensitivePaths;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class RepoFiles {

    public static final Set<String> IGNORED_DIRECTORIES =
            Set.of(
                    ".git",
                    ".hg",
                    ".svn",
                    "node_modules",
                    "target",
                    "build",
                    "out",
                    ".gradle",
                    ".idea",
                    ".vscode",
                    ".next",
                    "dist",
                    "__pycache__",
                    ".terraform");

    public record Options(
            Set<String> ignoredDirectories, long maxFileBytes, boolean excludeSensitive) {

        public static Options defaults() {
            return new Options(IGNORED_DIRECTORIES, 1024 * 1024, true);
        }

        public Options withMaxFileBytes(long bytes) {
            return new Options(ignoredDirectories, bytes, excludeSensitive);
        }

        public Options includingSensitive() {
            return new Options(ignoredDirectories, maxFileBytes, false);
        }
    }

    public record RepoFile(String path, Path absolute, long size) {

        public String extension() {
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            return dot > slash ? path.substring(dot + 1) : "";
        }
    }

    private RepoFiles() {}

    public static List<RepoFile> walk(Path root) {
        return walk(root, Options.defaults());
    }

    public static List<RepoFile> walk(Path root, Options options) {
        Path base = root.toAbsolutePath().normalize();
        List<RepoFile> files = new ArrayList<>();
        try {
            Files.walkFileTree(
                    base,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(base)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (attrs.isSymbolicLink()
                                    || options.ignoredDirectories()
                                            .contains(dir.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }
                            String relative = base.relativize(file).toString().replace('\\', '/');
                            if (attrs.size() > options.maxFileBytes()) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (options.excludeSensitive()
                                    && SensitivePaths.isSensitive(relative)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (looksBinary(file)) {
                                return FileVisitResult.CONTINUE;
                            }
                            files.add(new RepoFile(relative, file, attrs.size()));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw RookException.invalid("cannot walk repository", e);
        }
        files.sort(Comparator.comparing(RepoFile::path));
        return files;
    }

    public static Path resolveInside(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            throw RookException.invalid("empty path");
        }
        if (relative.indexOf('\0') >= 0) {
            throw RookException.invalid("path contains a null byte");
        }
        String normalizedSeparators = relative.replace('\\', '/');
        if (normalizedSeparators.startsWith("/") || normalizedSeparators.matches("^[A-Za-z]:.*")) {
            throw RookException.invalid("absolute paths are not allowed");
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(normalizedSeparators).normalize();
        if (!resolved.startsWith(base)) {
            throw RookException.invalid("path escapes the repository");
        }
        try {
            Path real = base.toRealPath();
            Path current = base;
            for (Path part : base.relativize(resolved)) {
                current = current.resolve(part);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(current)) {
                    Path target = current.toRealPath();
                    if (!target.startsWith(real)) {
                        throw RookException.invalid("path escapes the repository through a link");
                    }
                }
            }
        } catch (IOException e) {
            throw RookException.invalid("cannot resolve path", e);
        }
        return resolved;
    }

    public static String read(Path root, String relative, long maxBytes) {
        Path file = resolveInside(root, relative);
        try {
            if (!Files.isRegularFile(file)) {
                throw RookException.notFound("file", relative);
            }
            if (Files.size(file) > maxBytes) {
                throw RookException.invalid("file is larger than " + maxBytes + " bytes");
            }
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                if (b == 0) {
                    throw RookException.invalid("file is binary");
                }
            }
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw RookException.invalid("file is not text", e);
        } catch (IOException e) {
            throw RookException.invalid("cannot read " + relative, e);
        }
    }

    private static boolean looksBinary(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(8000);
            for (byte b : head) {
                if (b == 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
