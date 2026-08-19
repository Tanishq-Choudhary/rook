package dev.rook.workflow;

import dev.rook.core.job.JobState;
import java.util.UUID;

public record RunResult(Outcome outcome, UUID jobId, JobState state) {

    public enum Outcome {
        IDLE,
        ADVANCED,
        FINISHED,
        RETRY_SCHEDULED,
        DEAD,
        FAILED,
        LEASE_LOST,
        RELEASED,
        CRASHED
    }

    public static RunResult idle() {
        return new RunResult(Outcome.IDLE, null, null);
    }

    public boolean idleRun() {
        return outcome == Outcome.IDLE;
    }
}
