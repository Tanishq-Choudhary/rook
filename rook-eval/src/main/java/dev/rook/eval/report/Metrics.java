package dev.rook.eval.report;

import dev.rook.eval.run.FixtureResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Metrics {

    private Metrics() {}

    public static StrategyMetrics of(String strategy, List<FixtureResult> results) {
        int needing = 0;
        int noops = 0;
        int produced = 0;
        int accepted = 0;
        int acceptedNeeding = 0;
        int escapes = 0;
        int noopEdited = 0;
        int protectedEdited = 0;
        long cost = 0;
        long acceptedCost = 0;
        int tp = 0;
        int fp = 0;
        int fn = 0;
        List<Long> latencies = new ArrayList<>();
        Map<String, int[]> categories = new TreeMap<>();
        Map<String, Integer> failures = new TreeMap<>();
        for (FixtureResult result : results) {
            int[] counts = categories.computeIfAbsent(result.category(), key -> new int[3]);
            counts[0]++;
            tp += result.impactTruePositives();
            fp += result.impactFalsePositives();
            fn += result.impactFalseNegatives();
            cost += result.costMicros();
            latencies.add(result.latencyMillis());
            if (result.noop()) {
                noops++;
                if (!result.falseEdits().isEmpty()) {
                    noopEdited++;
                }
            } else {
                needing++;
                counts[1]++;
                if (result.accepted()) {
                    acceptedNeeding++;
                    acceptedCost += result.costMicros();
                }
                protectedEdited += result.falseEdits().size();
            }
            if (result.patchProduced()) {
                produced++;
            }
            if (result.accepted()) {
                accepted++;
                counts[2]++;
            }
            if (result.regressionEscape()) {
                escapes++;
            }
            if (!result.failure().isEmpty()) {
                failures.merge(result.failure(), 1, Integer::sum);
            }
        }
        Map<String, StrategyMetrics.CategoryMetrics> byCategory = new TreeMap<>();
        categories.forEach(
                (name, c) ->
                        byCategory.put(
                                name, new StrategyMetrics.CategoryMetrics(c[0], c[1], c[2])));
        return new StrategyMetrics(
                strategy,
                results.size(),
                needing,
                noops,
                ratio(tp, tp + fp),
                ratio(tp, tp + fn),
                produced,
                accepted,
                ratio(acceptedNeeding, needing),
                escapes,
                noopEdited,
                protectedEdited,
                cost,
                acceptedNeeding == 0 ? 0 : acceptedCost / acceptedNeeding,
                percentile(latencies, 50),
                percentile(latencies, 95),
                byCategory,
                failures);
    }

    static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    static long percentile(List<Long> values, int percent) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
