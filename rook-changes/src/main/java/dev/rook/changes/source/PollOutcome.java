package dev.rook.changes.source;

import dev.rook.core.change.ChangeSet;
import java.util.List;

public record PollOutcome(
        Status status,
        List<ChangeSet> changeSets,
        List<Snapshot> snapshots,
        String etag,
        String lastVersion) {

    public enum Status {
        UNCHANGED,
        BASELINE,
        CHANGED
    }

    public PollOutcome {
        changeSets = List.copyOf(changeSets);
        snapshots = List.copyOf(snapshots);
    }

    public static PollOutcome unchanged(String etag, String lastVersion) {
        return new PollOutcome(Status.UNCHANGED, List.of(), List.of(), etag, lastVersion);
    }

    public static PollOutcome baseline(Snapshot snapshot, String etag) {
        return new PollOutcome(
                Status.BASELINE, List.of(), List.of(snapshot), etag, snapshot.version());
    }

    public static PollOutcome changed(
            List<ChangeSet> changeSets, List<Snapshot> snapshots, String etag, String lastVersion) {
        return new PollOutcome(Status.CHANGED, changeSets, snapshots, etag, lastVersion);
    }
}
