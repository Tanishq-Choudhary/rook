package dev.rook.codeintel.impact;

public record ImpactLocation(
        String file,
        int line,
        int column,
        int endLine,
        String snippet,
        String enclosing,
        String referenceKind,
        Confidence confidence,
        String resolvedTarget,
        boolean test) {

    public String key() {
        return file + ":" + line + ":" + column + ":" + referenceKind;
    }
}
