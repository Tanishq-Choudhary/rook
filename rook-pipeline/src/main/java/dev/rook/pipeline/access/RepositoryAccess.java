package dev.rook.pipeline.access;

import java.nio.file.Path;
import java.util.UUID;

public interface RepositoryAccess {

    record Checkout(Path directory, String sha, String fullName, String defaultBranch) {}

    Checkout checkout(UUID repositoryId, Path directory, String pinnedSha);
}
