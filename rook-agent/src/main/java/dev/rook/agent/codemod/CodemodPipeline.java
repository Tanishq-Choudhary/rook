package dev.rook.agent.codemod;

import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.core.change.ChangeEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CodemodPipeline {

    public record Result(
            List<CodemodRunner.Outcome> outcomes, List<ImpactLocation> remaining, int edits) {

        public Result {
            outcomes = List.copyOf(outcomes);
            remaining = List.copyOf(remaining);
        }

        public boolean complete() {
            return remaining.isEmpty();
        }
    }

    private CodemodPipeline() {}

    public static Result run(Workspace workspace, List<ChangeEvent> events, ImpactReport report) {
        List<CodemodRunner.Outcome> outcomes = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        int edits = 0;
        for (CodemodSelector.Planned planned : CodemodSelector.select(events, report)) {
            CodemodRunner.Outcome outcome =
                    CodemodRunner.run(workspace, planned.codemod(), planned.locations());
            outcomes.add(outcome);
            edits += outcome.edits();
            outcome.handled().forEach(location -> handled.add(location.key()));
        }
        List<ImpactLocation> remaining = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Impact impact : report.impacts()) {
            for (ImpactLocation location : impact.locations()) {
                String key = impact.fingerprint() + "|" + location.key();
                if (!handled.contains(location.key()) && seen.add(key)) {
                    remaining.add(location);
                }
            }
        }
        return new Result(outcomes, remaining, edits);
    }
}
