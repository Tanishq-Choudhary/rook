package dev.rook.eval.report;

import java.util.Map;
import java.util.TreeMap;

public record StrategyMetrics(
        String strategy,
        int fixtures,
        int needingChange,
        int noops,
        double impactPrecision,
        double impactRecall,
        int patchesProduced,
        int accepted,
        double patchSuccessRate,
        int regressionEscapes,
        int noopFixturesEdited,
        int protectedFilesEdited,
        long totalCostMicros,
        long costPerAcceptedMicros,
        long latencyP50Millis,
        long latencyP95Millis,
        Map<String, CategoryMetrics> categories,
        Map<String, Integer> failures) {

    public record CategoryMetrics(int total, int needingChange, int accepted) {}

    public StrategyMetrics {
        categories = Map.copyOf(new TreeMap<>(categories));
        failures = Map.copyOf(new TreeMap<>(failures));
    }
}
