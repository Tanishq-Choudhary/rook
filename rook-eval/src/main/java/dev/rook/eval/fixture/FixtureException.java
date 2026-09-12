package dev.rook.eval.fixture;

public class FixtureException extends RuntimeException {

    public FixtureException(String message) {
        super(message);
    }

    public FixtureException(String message, Throwable cause) {
        super(message, cause);
    }
}
