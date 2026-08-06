package dev.rook.changes.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class InMemorySnapshots implements SnapshotStore {

    private final List<Snapshot> stored = new ArrayList<>();

    @Override
    public synchronized Optional<Snapshot> byVersion(String sourceId, String version) {
        return stored.stream()
                .filter(s -> s.sourceId().equals(sourceId) && s.version().equals(version))
                .findFirst();
    }

    @Override
    public synchronized Optional<Snapshot> byHash(String sourceId, String contentHash) {
        return stored.stream()
                .filter(s -> s.sourceId().equals(sourceId) && s.contentHash().equals(contentHash))
                .findFirst();
    }

    @Override
    public synchronized boolean save(Snapshot snapshot) {
        boolean clash =
                stored.stream()
                        .anyMatch(
                                s ->
                                        s.sourceId().equals(snapshot.sourceId())
                                                && (s.version().equals(snapshot.version())
                                                        || s.contentHash()
                                                                .equals(snapshot.contentHash())));
        if (clash) {
            return false;
        }
        stored.add(snapshot);
        return true;
    }

    public synchronized List<Snapshot> all() {
        return List.copyOf(stored);
    }
}
