package dev.rook.core.change;

import dev.rook.core.hash.Hashes;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ChangeSet(
        UUID id,
        String sourceId,
        SourceKind sourceKind,
        String fromVersion,
        String toVersion,
        List<ChangeEvent> events,
        List<MigrationNote> notes,
        Instant detectedAt) {

    public ChangeSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(fromVersion, "fromVersion");
        Objects.requireNonNull(toVersion, "toVersion");
        Objects.requireNonNull(detectedAt, "detectedAt");
        events =
                events == null
                        ? List.of()
                        : events.stream()
                                .sorted(Comparator.comparing(ChangeEvent::fingerprint))
                                .distinct()
                                .toList();
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public String key() {
        return key(sourceId, fromVersion, toVersion);
    }

    public static String key(String sourceId, String fromVersion, String toVersion) {
        return Hashes.sha256Hex(sourceId, fromVersion, toVersion);
    }

    public Severity highestSeverity() {
        return events.stream()
                .map(ChangeEvent::severity)
                .max(Comparator.naturalOrder())
                .orElse(Severity.ADVISORY);
    }

    public List<ChangeEvent> breakingEvents() {
        return events.stream().filter(ChangeEvent::isBreaking).toList();
    }

    public boolean requiresMigration() {
        return events.stream()
                .anyMatch(
                        e -> e.severity().atLeast(Severity.DEPRECATION) && !e.kind().isAdvisory());
    }
}
