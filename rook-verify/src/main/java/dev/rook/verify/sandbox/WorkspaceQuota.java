package dev.rook.verify.sandbox;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

final class WorkspaceQuota {

    private final long maxBytes;
    private final long maxFiles;

    WorkspaceQuota(long maxBytes, long maxFiles) {
        this.maxBytes = maxBytes;
        this.maxFiles = maxFiles;
    }

    boolean exceeded(List<Path> directories) {
        long[] used = new long[2];
        for (Path directory : directories) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.walkFileTree(
                        directory,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                used[0] += attrs.size();
                                used[1]++;
                                return used[0] > maxBytes || used[1] > maxFiles
                                        ? FileVisitResult.TERMINATE
                                        : FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult preVisitDirectory(
                                    Path dir, BasicFileAttributes attrs) {
                                used[1]++;
                                return used[1] > maxFiles
                                        ? FileVisitResult.TERMINATE
                                        : FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException e) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                continue;
            }
            if (used[0] > maxBytes || used[1] > maxFiles) {
                return true;
            }
        }
        return used[0] > maxBytes || used[1] > maxFiles;
    }
}
