package dev.rook.codeintel.impact;

public enum Confidence {
    TEXTUAL(0.3),
    HEURISTIC(0.6),
    RESOLVED(1.0);

    private final double weight;

    Confidence(double weight) {
        this.weight = weight;
    }

    public double weight() {
        return weight;
    }

    public static Confidence best(Confidence a, Confidence b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
