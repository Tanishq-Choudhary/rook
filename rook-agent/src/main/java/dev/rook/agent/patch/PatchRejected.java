package dev.rook.agent.patch;

public class PatchRejected extends RuntimeException {

    public enum Reason {
        EMPTY_SEARCH,
        NO_CHANGE,
        NOT_FOUND,
        AMBIGUOUS,
        MALFORMED,
        CONTEXT_MISMATCH,
        TOO_LARGE
    }

    private final Reason reason;

    public PatchRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
