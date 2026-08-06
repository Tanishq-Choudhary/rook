package dev.rook.changes.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

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
        return match(removed, added, removedTypes, addedTypes, Similarity::names);
    }

    public static List<Rename> match(
            List<String> removed,
            List<String> added,
            Map<String, String> removedTypes,
            Map<String, String> addedTypes,
            BiFunction<String, String, Double> scorer) {
        List<Rename> candidates = new ArrayList<>();
        for (String from : removed) {
            for (String to : added) {
                if (!removedTypes.get(from).equals(addedTypes.get(to))) {
                    continue;
                }
                double score = scorer.apply(from, to);
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
        Map<String, List<String>> removedByType = new LinkedHashMap<>();
        Map<String, List<String>> addedByType = new LinkedHashMap<>();
        for (String name : removed) {
            if (!usedFrom.contains(name)) {
                removedByType
                        .computeIfAbsent(removedTypes.get(name), k -> new ArrayList<>())
                        .add(name);
            }
        }
        for (String name : added) {
            if (!usedTo.contains(name)) {
                addedByType.computeIfAbsent(addedTypes.get(name), k -> new ArrayList<>()).add(name);
            }
        }
        for (Map.Entry<String, List<String>> group : removedByType.entrySet()) {
            List<String> partners = addedByType.get(group.getKey());
            if (partners != null && group.getValue().size() == 1 && partners.size() == 1) {
                accepted.add(
                        new Rename(group.getValue().get(0), partners.get(0), LONE_PAIR_CONFIDENCE));
            }
        }
        accepted.sort(Comparator.comparing(Rename::from));
        return accepted;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
