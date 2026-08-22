package dev.rook.agent.model;

public class ModelException extends RuntimeException {

    private final boolean retryable;

    public ModelException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ModelException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
