package dev.rook.github.publish;

import java.util.List;

public record PrReport(
        String repository,
        String sourceName,
        String fromVersion,
        String toVersion,
        List<String> changes,
        List<CallSite> callSites,
        Diff diff,
        Verification verification,
        List<String> risks,
        String strategy,
        String jobUrl) {

    public record CallSite(String file, int line, String note) {}

    public record Diff(int files, int added, int removed) {}

    public record Verification(
            int baselineRun,
            int baselineFailed,
            int patchedRun,
            int patchedFailed,
            List<String> flaky,
            List<String> gates) {

        public Verification {
            flaky = List.copyOf(flaky);
            gates = List.copyOf(gates);
        }
    }

    public PrReport {
        changes = List.copyOf(changes);
        callSites = List.copyOf(callSites);
        risks = List.copyOf(risks);
    }
}
