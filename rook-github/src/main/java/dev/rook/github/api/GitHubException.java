package dev.rook.github.api;

import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;

public class GitHubException extends RookException {

    private final int status;
    private final long retryAfterMillis;

    public GitHubException(int status, String message) {
        this(status, message, 0);
    }

    public GitHubException(int status, String message, long retryAfterMillis) {
        super(
                status >= 500 || status == 429 || status == 403
                        ? ErrorCode.UNAVAILABLE
                        : ErrorCode.INVALID,
                message);
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    public int status() {
        return status;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public boolean alreadyExists() {
        return status == 422;
    }
}
