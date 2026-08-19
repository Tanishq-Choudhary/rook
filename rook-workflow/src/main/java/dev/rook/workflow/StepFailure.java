package dev.rook.workflow;

import java.time.Duration;

public final class StepFailure {

    private StepFailure() {}

    public static class Retryable extends RuntimeException {

        private final Duration suggestedDelay;

        public Retryable(String message) {
            this(message, null, null);
        }

        public Retryable(String message, Throwable cause) {
            this(message, cause, null);
        }

        public Retryable(String message, Throwable cause, Duration suggestedDelay) {
            super(message, cause);
            this.suggestedDelay = suggestedDelay;
        }

        public Duration suggestedDelay() {
            return suggestedDelay;
        }
    }

    public static class Fatal extends RuntimeException {

        public Fatal(String message) {
            super(message);
        }

        public Fatal(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
