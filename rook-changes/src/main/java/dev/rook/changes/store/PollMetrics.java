package dev.rook.changes.store;

import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import java.time.Duration;

public interface PollMetrics {

    PollMetrics NOOP =
            new PollMetrics() {
                @Override
                public void polled(SourceKind kind, String outcome, Duration took) {}

                @Override
                public void ingested(ChangeSet set, boolean created) {}
            };

    void polled(SourceKind kind, String outcome, Duration took);

    void ingested(ChangeSet set, boolean created);
}
