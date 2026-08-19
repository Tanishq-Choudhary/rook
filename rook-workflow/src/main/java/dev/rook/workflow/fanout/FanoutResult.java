package dev.rook.workflow.fanout;

public record FanoutResult(Outcome outcome, int created, int existing) {

    public enum Outcome {
        JOBS_CREATED,
        NO_NEW_JOBS,
        NOT_ACTIONABLE,
        DUPLICATE,
        REJECTED
    }

    static FanoutResult of(Outcome outcome) {
        return new FanoutResult(outcome, 0, 0);
    }
}
