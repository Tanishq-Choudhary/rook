package dev.rook.eval.analysis;

import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.eval.fixture.FixtureSpec;
import java.util.Set;
import java.util.TreeSet;

public final class ImpactScorer {

    private ImpactScorer() {}

    public static ImpactScore score(FixtureSpec spec, ImpactReport report, Confidence minimum) {
        Set<String> predicted = new TreeSet<>();
        for (Impact impact : report.impacts()) {
            for (ImpactLocation location : impact.locations()) {
                if (location.confidence().compareTo(minimum) >= 0 && !location.test()) {
                    predicted.add(location.file() + ":" + location.line());
                }
            }
        }
        Set<String> expected = new TreeSet<>(spec.expectedKeys());
        Set<String> hits = new TreeSet<>(predicted);
        hits.retainAll(expected);
        Set<String> missed = new TreeSet<>(expected);
        missed.removeAll(predicted);
        Set<String> spurious = new TreeSet<>(predicted);
        spurious.removeAll(expected);
        return new ImpactScore(hits.size(), spurious.size(), missed.size(), missed, spurious);
    }
}
