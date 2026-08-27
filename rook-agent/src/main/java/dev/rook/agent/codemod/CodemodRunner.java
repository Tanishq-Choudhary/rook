package dev.rook.agent.codemod;

import dev.rook.agent.patch.PatchRejected;
import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspaceViolation;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.ArrayList;
import java.util.List;

public final class CodemodRunner {

    public record Outcome(
            String codemod, int files, int edits, List<String> skipped, Confidence confidence) {

        public Outcome {
            skipped = List.copyOf(skipped);
        }
    }

    private CodemodRunner() {}

    public static Outcome run(
            Workspace workspace, Codemod codemod, List<ImpactLocation> locations) {
        CodemodPlan plan = codemod.plan(workspace, locations);
        List<String> skipped = new ArrayList<>(plan.skipped());
        int files = 0;
        int edits = 0;
        for (CodemodPlan.FileRewrite rewrite : plan.rewrites()) {
            try {
                workspace.rewrite(rewrite.path(), rewrite.content());
                files++;
                edits += rewrite.edits();
            } catch (WorkspaceViolation | PatchRejected e) {
                skipped.add(rewrite.path() + ": " + e.getMessage());
            }
        }
        return new Outcome(plan.codemod(), files, edits, skipped, plan.confidence());
    }
}
