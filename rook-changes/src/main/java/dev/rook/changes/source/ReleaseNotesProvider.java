package dev.rook.changes.source;

import dev.rook.changes.feed.ReleaseEntry;
import java.util.Optional;

public interface ReleaseNotesProvider {

    Optional<ReleaseEntry> forVersion(WatchedSource source, String version);

    static ReleaseNotesProvider none() {
        return (source, version) -> Optional.empty();
    }
}
