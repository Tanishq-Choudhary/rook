package dev.rook.changes.source;

import dev.rook.core.change.SourceKind;

public interface SourcePoller {

    SourceKind kind();

    PollOutcome poll(WatchedSource source);
}
