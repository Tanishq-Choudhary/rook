package dev.rook.agent.strategy;

import dev.rook.agent.codemod.CodemodPipeline;
import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.model.Usage;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactLocation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class CodemodOnlyStrategy implements Strategy {

    public static final String NAME = "codemod-only";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StrategyOutcome run(AgentTask task, Workspace workspace) {
        long started = System.nanoTime();
        CodemodPipeline.Result result =
                CodemodPipeline.run(workspace, task.events(), task.report());
        List<String> unresolved = new ArrayList<>();
        for (ImpactLocation location : result.remaining()) {
            unresolved.add(describe(location));
        }
        unresolved.addAll(skipped(result));
        return new StrategyOutcome(
                NAME,
                workspace.diff(),
                workspace.changedPaths(),
                unresolved,
                Usage.NONE,
                0,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                false,
                result.complete() ? "COMPLETE" : "PARTIAL",
                List.of());
    }

    static String describe(ImpactLocation location) {
        return location.file() + ":" + location.line() + " " + location.referenceKind();
    }

    private static List<String> skipped(CodemodPipeline.Result result) {
        List<String> notes = new ArrayList<>();
        result.outcomes().forEach(outcome -> outcome.skipped().forEach(notes::add));
        return notes;
    }
}
