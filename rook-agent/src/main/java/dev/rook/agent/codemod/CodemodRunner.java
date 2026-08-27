package dev.rook.agent.codemod;

import dev.rook.agent.patch.PatchRejected;
import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspaceViolation;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CodemodRunner {

    public record Outcome(
            String codemod,
            int files,
            int edits,
            List<String> skipped,
            Confidence confidence,
            List<ImpactLocation> handled) {

        public Outcome {
            skipped = List.copyOf(skipped);
            handled = List.copyOf(handled);
        }
    }

    private CodemodRunner() {}

    public static Outcome run(
            Workspace workspace, Codemod codemod, List<ImpactLocation> locations) {
        CodemodPlan plan = codemod.plan(workspace, locations);
        List<String> skipped = new ArrayList<>(plan.skipped());
        int files = 0;
        int edits = 0;
        Set<String> rewritten = new HashSet<>();
        for (CodemodPlan.FileRewrite rewrite : plan.rewrites()) {
            try {
                workspace.rewrite(rewrite.path(), rewrite.content());
                files++;
                rewritten.add(rewrite.path());
                edits += rewrite.edits();
            } catch (WorkspaceViolation | PatchRejected e) {
                skipped.add(rewrite.path() + ": " + e.getMessage());
            }
        }
        List<ImpactLocation> handled =
                plan.handled().stream().filter(l -> rewritten.contains(l.file())).toList();
        return new Outcome(plan.codemod(), files, edits, skipped, plan.confidence(), handled);
    }
}
