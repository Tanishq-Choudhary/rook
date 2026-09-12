package dev.rook.verify.sandbox;

import java.time.Duration;

public record DockerConfig(
        String binary,
        String user,
        String tmpfsSize,
        boolean pullMissingImages,
        Duration killGrace,
        long maxWorkspaceBytes,
        long maxWorkspaceFiles) {

    public DockerConfig {
        if (binary == null || binary.isBlank() || user == null || user.isBlank()) {
            throw new IllegalArgumentException("docker binary and user are required");
        }
        if (!tmpfsSize.matches("[0-9]+[mg]")) {
            throw new IllegalArgumentException("tmpfs size must look like 256m");
        }
        if (maxWorkspaceBytes < 1024 || maxWorkspaceFiles < 1) {
            throw new IllegalArgumentException("workspace limits must be positive");
        }
    }

    public DockerConfig(
            String binary,
            String user,
            String tmpfsSize,
            boolean pullMissingImages,
            Duration killGrace) {
        this(binary, user, tmpfsSize, pullMissingImages, killGrace, 1L << 30, 200_000);
    }

    public static DockerConfig standard() {
        return new DockerConfig("docker", "1000:1000", "256m", false, Duration.ofSeconds(10));
    }

    public DockerConfig withUser(String newUser) {
        return new DockerConfig(
                binary,
                newUser,
                tmpfsSize,
                pullMissingImages,
                killGrace,
                maxWorkspaceBytes,
                maxWorkspaceFiles);
    }

    public DockerConfig withBinary(String newBinary) {
        return new DockerConfig(
                newBinary,
                user,
                tmpfsSize,
                pullMissingImages,
                killGrace,
                maxWorkspaceBytes,
                maxWorkspaceFiles);
    }

    public DockerConfig withWorkspaceLimits(long bytes, long files) {
        return new DockerConfig(
                binary, user, tmpfsSize, pullMissingImages, killGrace, bytes, files);
    }
}
