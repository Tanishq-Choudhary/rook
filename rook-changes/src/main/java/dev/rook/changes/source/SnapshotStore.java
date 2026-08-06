package dev.rook.changes.source;

import java.util.Optional;

public interface SnapshotStore {

    Optional<Snapshot> byVersion(String sourceId, String version);

    Optional<Snapshot> byHash(String sourceId, String contentHash);

    boolean save(Snapshot snapshot);
}
