package dev.rook.agent.loop;

import java.time.Duration;

public record AgentBudget(
        int maxIterations,
        long maxTokens,
        long maxCostMicros,
        Duration wallClock,
        int maxRepairRounds,
        int maxToolCallsPerTurn,
        int maxToolCalls) {

    public AgentBudget {
        if (maxIterations < 1
                || maxTokens < 1
                || maxCostMicros < 0
                || wallClock.isNegative()
                || wallClock.isZero()
                || maxRepairRounds < 0
                || maxToolCallsPerTurn < 1
                || maxToolCalls < 1) {
            throw new IllegalArgumentException("invalid agent budget");
        }
    }

    public static AgentBudget standard() {
        return new AgentBudget(12, 400_000, 2_000_000, Duration.ofMinutes(10), 3, 8, 60);
    }

    public AgentBudget withMaxIterations(int iterations) {
        return new AgentBudget(
                iterations,
                maxTokens,
                maxCostMicros,
                wallClock,
                maxRepairRounds,
                maxToolCallsPerTurn,
                maxToolCalls);
    }

    public AgentBudget withMaxTokens(long tokens) {
        return new AgentBudget(
                maxIterations,
                tokens,
                maxCostMicros,
                wallClock,
                maxRepairRounds,
                maxToolCallsPerTurn,
                maxToolCalls);
    }

    public AgentBudget withMaxCostMicros(long micros) {
        return new AgentBudget(
                maxIterations,
                maxTokens,
                micros,
                wallClock,
                maxRepairRounds,
                maxToolCallsPerTurn,
                maxToolCalls);
    }

    public AgentBudget withWallClock(Duration duration) {
        return new AgentBudget(
                maxIterations,
                maxTokens,
                maxCostMicros,
                duration,
                maxRepairRounds,
                maxToolCallsPerTurn,
                maxToolCalls);
    }

    public AgentBudget withMaxRepairRounds(int rounds) {
        return new AgentBudget(
                maxIterations,
                maxTokens,
                maxCostMicros,
                wallClock,
                rounds,
                maxToolCallsPerTurn,
                maxToolCalls);
    }

    public AgentBudget withToolCallLimits(int perTurn, int total) {
        return new AgentBudget(
                maxIterations,
                maxTokens,
                maxCostMicros,
                wallClock,
                maxRepairRounds,
                perTurn,
                total);
    }
}
