package dev.rook.core.error;

public enum ErrorCode {
    INVALID(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    PAYLOAD_TOO_LARGE(413),
    RATE_LIMITED(429),
    BUDGET_EXCEEDED(402),
    POLICY_DENIED(422),
    UNAVAILABLE(503),
    INTERNAL(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
