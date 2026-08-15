package dev.rook.core.job;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum JobState {
    PENDING,
    INDEXING,
    PROPOSING,
    VERIFYING,
    AWAITING_APPROVAL,
    PUBLISHING,
    COMPLETED,
    NO_IMPACT,
    REJECTED,
    FAILED,
    DEAD,
    SUPERSEDED,
    CANCELLED;

    private static final Map<JobState, Set<JobState>> TRANSITIONS = new EnumMap<>(JobState.class);

    static {
        TRANSITIONS.put(PENDING, EnumSet.of(INDEXING, SUPERSEDED, CANCELLED));
        TRANSITIONS.put(INDEXING, EnumSet.of(PROPOSING, NO_IMPACT, FAILED, DEAD, CANCELLED));
        TRANSITIONS.put(PROPOSING, EnumSet.of(VERIFYING, REJECTED, FAILED, DEAD, CANCELLED));
        TRANSITIONS.put(
                VERIFYING,
                EnumSet.of(AWAITING_APPROVAL, PUBLISHING, REJECTED, FAILED, DEAD, CANCELLED));
        TRANSITIONS.put(AWAITING_APPROVAL, EnumSet.of(PUBLISHING, REJECTED, CANCELLED));
        TRANSITIONS.put(PUBLISHING, EnumSet.of(COMPLETED, FAILED, DEAD));
        for (JobState state : values()) {
            TRANSITIONS.putIfAbsent(state, EnumSet.noneOf(JobState.class));
        }
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }

    public boolean isRunning() {
        return this == INDEXING || this == PROPOSING || this == VERIFYING || this == PUBLISHING;
    }

    public boolean isClaimable() {
        return this == PENDING || isRunning();
    }

    public boolean canTransitionTo(JobState next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public Set<JobState> successors() {
        return Set.copyOf(TRANSITIONS.get(this));
    }
}
