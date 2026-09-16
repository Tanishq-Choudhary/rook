package dev.rook.eval.verify;

import java.util.List;

public record Verdict(
        boolean compiled,
        int visibleRun,
        List<String> visibleFailures,
        int independentRun,
        List<String> independentFailures,
        long millis,
        String detail) {

    public Verdict {
        visibleFailures = List.copyOf(visibleFailures);
        independentFailures = List.copyOf(independentFailures);
        detail = detail == null ? "" : detail;
    }

    public static Verdict notCompiling(long millis, String detail) {
        return new Verdict(false, 0, List.of(), 0, List.of(), millis, detail);
    }

    public boolean visiblePassed() {
        return compiled && visibleRun > 0 && visibleFailures.isEmpty();
    }

    public boolean independentPassed() {
        return compiled && independentRun > 0 && independentFailures.isEmpty();
    }

    public boolean accepted() {
        return visiblePassed() && independentPassed();
    }
}
