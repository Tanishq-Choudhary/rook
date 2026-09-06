package dev.rook.verify.build;

public record TestCaseResult(String id, Outcome outcome, String message, double seconds) {

    public enum Outcome {
        PASSED,
        SKIPPED,
        FAILED,
        ERROR;

        public boolean broken() {
            return this == FAILED || this == ERROR;
        }
    }

    public boolean broken() {
        return outcome.broken();
    }
}
