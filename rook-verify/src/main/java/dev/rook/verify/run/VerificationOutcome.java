package dev.rook.verify.run;

import dev.rook.verify.build.RunSummary;
import dev.rook.verify.compare.Comparison;
import dev.rook.verify.compare.FlakeAnalyzer;
import dev.rook.verify.gate.GateReport;
import java.time.Duration;
import java.util.List;

public record VerificationOutcome(
        boolean passed,
        String unsupportedReason,
        GateReport gates,
        RunSummary baseline,
        RunSummary patched,
        Comparison comparison,
        FlakeAnalyzer.Verdict flakes,
        boolean warmedCache,
        Duration elapsed) {

    public static VerificationOutcome unsupported(String reason, Duration elapsed) {
        return new VerificationOutcome(
                false, reason, new GateReport(List.of()), null, null, null, null, false, elapsed);
    }

    public boolean supported() {
        return unsupportedReason == null;
    }

    public String summary() {
        if (!supported()) {
            return "verification is not possible: " + unsupportedReason;
        }
        return passed ? gates.summary() : "rejected: " + gates.summary();
    }
}
