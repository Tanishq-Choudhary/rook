package dev.rook.changes.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Similarity {

    private static final double CONTAINMENT_SCORE = 0.75;

    private Similarity() {}

    public static double names(String left, String right) {
        List<String> a = tokens(left);
        List<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        String joinedA = String.join("", a);
        String joinedB = String.join("", b);
        if (joinedA.equals(joinedB)) {
            return 1;
        }
        double edit =
                1.0
                        - (double) distance(joinedA, joinedB)
                                / Math.max(joinedA.length(), joinedB.length());
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> common = new HashSet<>(a);
        common.retainAll(new HashSet<>(b));
        double overlap = (double) common.size() / union.size();
        double containment = common.size() == Math.min(a.size(), b.size()) ? CONTAINMENT_SCORE : 0;
        return Math.max(Math.max(edit, overlap), containment);
    }

    public static List<String> tokens(String name) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(tokens, current);
                continue;
            }
            boolean boundary =
                    current.length() > 0
                            && Character.isUpperCase(c)
                            && (Character.isLowerCase(current.charAt(current.length() - 1))
                                    || (i + 1 < name.length()
                                            && Character.isLowerCase(name.charAt(i + 1))
                                            && Character.isUpperCase(
                                                    current.charAt(current.length() - 1))));
            if (boundary) {
                flush(tokens, current);
            }
            current.append(c);
        }
        flush(tokens, current);
        return tokens;
    }

    public static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] =
                        Math.min(
                                Math.min(current[j - 1] + 1, previous[j] + 1),
                                previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString().toLowerCase(Locale.ROOT));
            current.setLength(0);
        }
    }
}
