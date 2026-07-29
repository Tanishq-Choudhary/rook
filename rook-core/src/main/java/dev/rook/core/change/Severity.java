package dev.rook.core.change;

public enum Severity {
    ADVISORY,
    ADDITIVE,
    DEPRECATION,
    BREAKING;

    public boolean atLeast(Severity other) {
        return compareTo(other) >= 0;
    }
}
