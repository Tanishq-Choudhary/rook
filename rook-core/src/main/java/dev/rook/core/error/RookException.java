package dev.rook.core.error;

public class RookException extends RuntimeException {

    private final ErrorCode code;

    public RookException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public RookException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public static RookException notFound(String kind, Object id) {
        return new RookException(ErrorCode.NOT_FOUND, kind + " " + id + " not found");
    }

    public static RookException conflict(String message) {
        return new RookException(ErrorCode.CONFLICT, message);
    }

    public static RookException invalid(String message) {
        return new RookException(ErrorCode.INVALID, message);
    }

    public static RookException invalid(String message, Throwable cause) {
        return new RookException(ErrorCode.INVALID, message, cause);
    }

    public static RookException forbidden(String message) {
        return new RookException(ErrorCode.FORBIDDEN, message);
    }

    public static RookException unauthorized(String message) {
        return new RookException(ErrorCode.UNAUTHORIZED, message);
    }

    public static RookException budgetExceeded(String message) {
        return new RookException(ErrorCode.BUDGET_EXCEEDED, message);
    }

    public static RookException policyDenied(String message) {
        return new RookException(ErrorCode.POLICY_DENIED, message);
    }

    public static RookException unavailable(String message, Throwable cause) {
        return new RookException(ErrorCode.UNAVAILABLE, message, cause);
    }
}
