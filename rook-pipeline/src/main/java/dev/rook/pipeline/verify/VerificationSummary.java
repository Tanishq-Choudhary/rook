package dev.rook.pipeline.verify;

import dev.rook.verify.build.RunSummary;
import dev.rook.verify.gate.GateResult;
import dev.rook.verify.run.VerificationOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VerificationSummary {

    private VerificationSummary() {}

    public static Integer executed(RunSummary run) {
        return run == null ? null : run.report().executed();
    }

    public static Integer broken(RunSummary run) {
        return run == null ? null : run.report().broken().size();
    }

    public static Map<String, Object> report(VerificationOutcome outcome) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("supported", outcome.supported());
        report.put("warmedCache", outcome.warmedCache());
        report.put(
                "reason", outcome.unsupportedReason() == null ? "" : outcome.unsupportedReason());
        List<Map<String, Object>> gates = new ArrayList<>();
        for (GateResult gate : outcome.gates().results()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", gate.name());
            entry.put("passed", gate.passed());
            entry.put("detail", gate.detail());
            gates.add(entry);
        }
        report.put("gates", gates);
        report.put("baseline", run(outcome.baseline()));
        report.put("patched", run(outcome.patched()));
        if (outcome.comparison() != null) {
            report.put("regressions", outcome.comparison().regressions());
            report.put("fixed", outcome.comparison().fixed());
            report.put("preExisting", outcome.comparison().preExisting());
        }
        if (outcome.flakes() != null) {
            report.put("flaky", outcome.flakes().flaky());
            report.put("confirmedRegressions", outcome.flakes().confirmed());
        }
        return report;
    }

    private static Map<String, Object> run(RunSummary run) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (run == null) {
            return summary;
        }
        summary.put("status", run.status().name());
        summary.put("executed", run.report().executed());
        summary.put("failed", run.report().broken().size());
        return summary;
    }
}
