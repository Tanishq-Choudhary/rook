package dev.rook.agent.workspace;

public class WorkspaceViolation extends RuntimeException {

    public enum Code {
        OUTSIDE_ROOT,
        DENIED,
        TOO_LARGE,
        LIMIT_REACHED,
        NOT_TEXT,
        NOT_FOUND,
        IO
    }

    private final Code code;

    public WorkspaceViolation(Code code, String message) {
        super(message);
        this.code = code;
    }

    public WorkspaceViolation(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
