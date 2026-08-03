package dev.rook.changes.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RenameMatcher {

    public static final double NAME_THRESHOLD = 0.6;
    public static final double LONE_PAIR_CONFIDENCE = 0.4;

    private RenameMatcher() {}

    public record Rename(String from, String to, double confidence) {}

    public static List<Rename> match(
            List<String> removed,
            List<String> added,
            Map<String, String> removedTypes,
            Map<String, String> addedTypes) {
        List<Rename> candidates = new ArrayList<>();
        for (String from : removed) {
            for (String to : added) {
                if (!removedTypes.get(from).equals(addedTypes.get(to))) {
                    continue;
                }
                double score = Similarity.names(from, to);
                if (score >= NAME_THRESHOLD) {
                    candidates.add(new Rename(from, to, round(score)));
                }
            }
        }
        candidates.sort(
                Comparator.comparingDouble(Rename::confidence)
                        .reversed()
                        .thenComparing(Rename::from)
                        .thenComparing(Rename::to));
        List<Rename> accepted = new ArrayList<>();
        Set<String> usedFrom = new HashSet<>();
        Set<String> usedTo = new HashSet<>();
        for (Rename candidate : candidates) {
            if (usedFrom.contains(candidate.from()) || usedTo.contains(candidate.to())) {
                continue;
            }
            accepted.add(candidate);
            usedFrom.add(candidate.from());
            usedTo.add(candidate.to());
        }
        List<String> leftoverRemoved = removed.stream().filter(n -> !usedFrom.contains(n)).toList();
        List<String> leftoverAdded = added.stream().filter(n -> !usedTo.contains(n)).toList();
        if (leftoverRemoved.size() == 1 && leftoverAdded.size() == 1) {
            String from = leftoverRemoved.get(0);
            String to = leftoverAdded.get(0);
            if (removedTypes.get(from).equals(addedTypes.get(to))) {
                accepted.add(new Rename(from, to, LONE_PAIR_CONFIDENCE));
            }
        }
        accepted.sort(Comparator.comparing(Rename::from));
        return accepted;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
