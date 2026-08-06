package dev.rook.changes.store;

import dev.rook.changes.source.PollOutcome;

public record PollReport(String sourceId, PollOutcome.Status status, int changeSets, String error) {

    public boolean failed() {
        return error != null;
    }

    public static PollReport failure(String sourceId, String error) {
        return new PollReport(sourceId, null, 0, error);
    }
}
