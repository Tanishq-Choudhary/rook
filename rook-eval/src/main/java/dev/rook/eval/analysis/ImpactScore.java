package dev.rook.eval.analysis;

import java.util.Set;

public record ImpactScore(
        int truePositives,
        int falsePositives,
        int falseNegatives,
        Set<String> missed,
        Set<String> spurious) {

    public ImpactScore {
        missed = Set.copyOf(missed);
        spurious = Set.copyOf(spurious);
    }

    public double precision() {
        int reported = truePositives + falsePositives;
        return reported == 0 ? 1.0 : (double) truePositives / reported;
    }

    public double recall() {
        int relevant = truePositives + falseNegatives;
        return relevant == 0 ? 1.0 : (double) truePositives / relevant;
    }

    public boolean exact() {
        return falsePositives == 0 && falseNegatives == 0;
    }
}
