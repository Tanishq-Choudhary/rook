package dev.rook.changes.diff;

import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.Severity;
import dev.rook.core.change.SymbolRef;
import java.util.Map;

public record DiffContext(String sourceId, String fromVersion, String toVersion) {

    public ChangeEvent event(
            ChangeKind kind, SymbolRef subject, Map<String, String> attributes, String summary) {
        return ChangeEvent.of(sourceId, fromVersion, toVersion, kind, subject, attributes, summary);
    }

    public ChangeEvent event(
            ChangeKind kind,
            Severity severity,
            SymbolRef subject,
            Map<String, String> attributes,
            String summary) {
        return ChangeEvent.of(
                sourceId, fromVersion, toVersion, kind, severity, subject, attributes, summary);
    }
}
