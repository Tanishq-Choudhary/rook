package dev.rook.codeintel.impact;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public record ImpactReport(List<Impact> impacts, List<Impact> unmatched, Stats stats) {

    public record Stats(
            int eventsAnalyzed,
            int eventsWithImpact,
            int locations,
            int affectedFiles,
            Map<Confidence, Integer> byConfidence) {}

    public ImpactReport {
        impacts = List.copyOf(impacts);
        unmatched = List.copyOf(unmatched);
    }

    public static ImpactReport of(List<Impact> all) {
        List<Impact> matched =
                all.stream()
                        .filter(i -> !i.isEmpty())
                        .sorted(
                                Comparator.comparing(Impact::severity)
                                        .reversed()
                                        .thenComparing(i -> i.kind().name())
                                        .thenComparing(Impact::subject))
                        .toList();
        List<Impact> unmatched = all.stream().filter(Impact::isEmpty).toList();
        Map<Confidence, Integer> counts = new EnumMap<>(Confidence.class);
        for (Confidence confidence : Confidence.values()) {
            counts.put(confidence, 0);
        }
        Set<String> files = new TreeSet<>();
        int locations = 0;
        for (Impact impact : matched) {
            for (ImpactLocation location : impact.locations()) {
                locations++;
                files.add(location.file());
                counts.merge(location.confidence(), 1, Integer::sum);
            }
        }
        return new ImpactReport(
                matched,
                unmatched,
                new Stats(all.size(), matched.size(), locations, files.size(), counts));
    }

    public List<String> affectedFiles() {
        Set<String> files = new TreeSet<>();
        impacts.forEach(i -> i.locations().forEach(l -> files.add(l.file())));
        return List.copyOf(files);
    }

    public boolean hasImpact() {
        return !impacts.isEmpty();
    }

    public boolean touchesMainCode() {
        return impacts.stream().anyMatch(Impact::affectsMainCode);
    }
}
