package dev.rook.core.change;

import dev.rook.core.hash.Hashes;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record ChangeEvent(
        String fingerprint,
        ChangeKind kind,
        Severity severity,
        SymbolRef subject,
        Map<String, String> attributes,
        String summary) {

    public ChangeEvent {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(summary, "summary");
        attributes = Map.copyOf(new TreeMap<>(attributes == null ? Map.of() : attributes));
    }

    public static ChangeEvent of(
            String sourceId,
            String fromVersion,
            String toVersion,
            ChangeKind kind,
            SymbolRef subject,
            Map<String, String> attributes,
            String summary) {
        return of(
                sourceId,
                fromVersion,
                toVersion,
                kind,
                kind.defaultSeverity(),
                subject,
                attributes,
                summary);
    }

    public static ChangeEvent of(
            String sourceId,
            String fromVersion,
            String toVersion,
            ChangeKind kind,
            Severity severity,
            SymbolRef subject,
            Map<String, String> attributes,
            String summary) {
        String fingerprint =
                fingerprint(
                        sourceId,
                        fromVersion,
                        toVersion,
                        kind,
                        subject,
                        attributes == null ? Map.of() : attributes);
        return new ChangeEvent(fingerprint, kind, severity, subject, attributes, summary);
    }

    public static String fingerprint(
            String sourceId,
            String fromVersion,
            String toVersion,
            ChangeKind kind,
            SymbolRef subject,
            Map<String, String> attributes) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(attributes)
                .forEach((k, v) -> canonical.append(k).append('=').append(v).append(';'));
        return Hashes.sha256Hex(
                sourceId,
                fromVersion,
                toVersion,
                kind.name(),
                subject.kind().name(),
                subject.name(),
                canonical.toString());
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    public boolean isBreaking() {
        return severity == Severity.BREAKING;
    }
}
