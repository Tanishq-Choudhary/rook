package dev.rook.verify.gate;

import java.util.List;

public record GateReport(List<GateResult> results) {

    public GateReport {
        results = List.copyOf(results);
    }

    public boolean passed() {
        return !results.isEmpty() && results.stream().allMatch(GateResult::passed);
    }

    public List<GateResult> failures() {
        return results.stream().filter(r -> !r.passed()).toList();
    }

    public String summary() {
        if (passed()) {
            return "all " + results.size() + " gates passed";
        }
        return failures().stream()
                .map(r -> r.name() + ": " + r.detail())
                .reduce((a, b) -> a + "; " + b)
                .orElse("no gates ran");
    }
}
