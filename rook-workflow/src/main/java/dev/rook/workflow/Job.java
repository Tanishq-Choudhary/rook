package dev.rook.workflow;

import dev.rook.core.job.JobState;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record Job(
        UUID id,
        UUID changeSetId,
        UUID repositoryId,
        JobState state,
        int attempt,
        int maxAttempts,
        int priority,
        Instant nextRunAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        long fence,
        String lastError,
        JsonNode checkpoint,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt) {

    public boolean leased() {
        return leaseOwner != null;
    }

    public String checkpointText(String key) {
        JsonNode value = checkpoint.path(key);
        return value.isString() ? value.asString() : null;
    }
}
