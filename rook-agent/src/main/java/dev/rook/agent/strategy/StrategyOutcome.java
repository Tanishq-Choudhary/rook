package dev.rook.agent.strategy;

import dev.rook.agent.loop.TraceStep;
import dev.rook.agent.model.Usage;
import java.util.List;

public record StrategyOutcome(
        String strategy,
        String diff,
        List<String> changedPaths,
        List<String> unresolved,
        Usage usage,
        long costMicros,
        long elapsedMillis,
        boolean verified,
        String status,
        List<TraceStep> trace) {

    public StrategyOutcome {
        changedPaths = List.copyOf(changedPaths);
        unresolved = List.copyOf(unresolved);
        trace = List.copyOf(trace);
    }

    public boolean hasPatch() {
        return !diff.isEmpty();
    }
}
