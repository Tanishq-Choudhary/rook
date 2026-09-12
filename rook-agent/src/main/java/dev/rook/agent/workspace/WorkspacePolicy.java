package dev.rook.agent.workspace;

import dev.rook.core.security.SensitivePaths;
import java.util.List;
import java.util.Set;

public record WorkspacePolicy(
        long maxFileBytes,
        int maxFilesTouched,
        int maxEdits,
        int maxChangedLines,
        int maxCopiedFiles,
        boolean allowBuildFiles) {

    private static final Set<String> BUILD_FILES =
            Set.of(
                    "pom.xml",
                    "build.gradle",
                    "build.gradle.kts",
                    "settings.gradle",
                    "settings.gradle.kts",
                    "gradle.properties",
                    "package.json",
                    "package-lock.json",
                    "yarn.lock",
                    "pnpm-lock.yaml",
                    "requirements.txt",
                    "Dockerfile",
                    "Makefile",
                    "mvnw",
                    "mvnw.cmd",
                    "gradlew");

    private static final List<String> NEVER_READ_PREFIXES = List.of(".git/");

    private static final List<String> NEVER_EDIT_PREFIXES =
            List.of(".git/", ".github/workflows/", ".mvn/");

    private static final Set<String> NEVER_EDIT_FILES = Set.of(".rook.yml", ".rook.yaml");

    public WorkspacePolicy {
        if (maxFileBytes < 1
                || maxFilesTouched < 1
                || maxEdits < 1
                || maxChangedLines < 1
                || maxCopiedFiles < 1) {
            throw new IllegalArgumentException("workspace limits must be positive");
        }
    }

    public static WorkspacePolicy standard() {
        return new WorkspacePolicy(512 * 1024, 20, 60, 600, 20_000, false);
    }

    public WorkspacePolicy withLimits(int filesTouched, int edits, int changedLines) {
        return new WorkspacePolicy(
                maxFileBytes, filesTouched, edits, changedLines, maxCopiedFiles, allowBuildFiles);
    }

    public WorkspacePolicy withMaxFileBytes(long bytes) {
        return new WorkspacePolicy(
                bytes, maxFilesTouched, maxEdits, maxChangedLines, maxCopiedFiles, allowBuildFiles);
    }

    public WorkspacePolicy withMaxCopiedFiles(int files) {
        return new WorkspacePolicy(
                maxFileBytes, maxFilesTouched, maxEdits, maxChangedLines, files, allowBuildFiles);
    }

    public WorkspacePolicy allowingBuildFiles() {
        return new WorkspacePolicy(
                maxFileBytes, maxFilesTouched, maxEdits, maxChangedLines, maxCopiedFiles, true);
    }

    public static boolean isBuildFile(String path) {
        String normalized = normalize(path);
        return BUILD_FILES.contains(normalized.substring(normalized.lastIndexOf('/') + 1));
    }

    public static boolean isProtectedFromEdits(String path) {
        String normalized = normalize(path);
        if (NEVER_EDIT_FILES.contains(normalized)) {
            return true;
        }
        return NEVER_EDIT_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    public void checkRead(String path) {
        String normalized = normalize(path);
        if (SensitivePaths.isSensitive(normalized)) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.DENIED,
                    normalized + " may hold secrets and is not available");
        }
        for (String prefix : NEVER_READ_PREFIXES) {
            if (normalized.startsWith(prefix)
                    || normalized.equals(prefix.substring(0, prefix.length() - 1))) {
                throw new WorkspaceViolation(
                        WorkspaceViolation.Code.DENIED, normalized + " is repository metadata");
            }
        }
    }

    public void checkEdit(String path) {
        checkRead(path);
        String normalized = normalize(path);
        for (String prefix : NEVER_EDIT_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                throw new WorkspaceViolation(
                        WorkspaceViolation.Code.DENIED,
                        normalized + " cannot be changed by a migration");
            }
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (NEVER_EDIT_FILES.contains(normalized)) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.DENIED,
                    normalized + " is repository configuration for Rook");
        }
        if (!allowBuildFiles && BUILD_FILES.contains(name)) {
            throw new WorkspaceViolation(
                    WorkspaceViolation.Code.DENIED,
                    normalized + " is a build file and the policy does not allow changing it");
        }
    }

    static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
