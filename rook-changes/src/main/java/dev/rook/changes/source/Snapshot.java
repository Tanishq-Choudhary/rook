package dev.rook.changes.source;

import java.time.Instant;
import java.util.UUID;

public record Snapshot(
        UUID id,
        String sourceId,
        String version,
        String contentHash,
        byte[] content,
        Instant fetchedAt) {}
