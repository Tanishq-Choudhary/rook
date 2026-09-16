package dev.rook.eval.run;

import java.util.List;

public record FixtureResult(
        String fixtureId,
        String category,
        String difficulty,
        boolean noop,
        String strategy,
        String decision,
        int impactTruePositives,
        int impactFalsePositives,
        int impactFalseNegatives,
        boolean patchProduced,
        List<String> changedPaths,
        List<String> falseEdits,
        boolean visiblePassed,
        boolean independentPassed,
        boolean regressionEscape,
        boolean accepted,
        long costMicros,
        long latencyMillis,
        String status,
        List<String> unresolved,
        String failure) {

    public FixtureResult {
        changedPaths = List.copyOf(changedPaths);
        falseEdits = List.copyOf(falseEdits);
        unresolved = List.copyOf(unresolved);
    }
}
