package dev.rook.agent.loop;

import dev.rook.agent.model.Usage;
import java.util.List;

public record AgentResult(
        Status status,
        String summary,
        double confidence,
        List<String> unresolved,
        List<String> risks,
        String diff,
        List<String> changedPaths,
        Usage usage,
        long costMicros,
        int iterations,
        int repairRounds,
        List<TraceStep> trace) {

    public enum Status {
        VERIFIED,
        UNVERIFIED,
        NO_CHANGES,
        TESTS_FAILING,
        STOPPED_WITHOUT_FINISH,
        ITERATION_LIMIT,
        TOKEN_BUDGET,
        COST_BUDGET,
        TIME_BUDGET,
        TOOL_CALL_LIMIT,
        MODEL_FAILED
    }

    public AgentResult {
        unresolved = List.copyOf(unresolved);
        risks = List.copyOf(risks);
        changedPaths = List.copyOf(changedPaths);
        trace = List.copyOf(trace);
    }

    public boolean hasPatch() {
        return !diff.isEmpty();
    }

    public boolean acceptable() {
        return status == Status.VERIFIED && unresolved.isEmpty();
    }
}
