package dev.rook.codeintel.impact;

import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.Severity;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Impact(
        String fingerprint,
        ChangeKind kind,
        Severity severity,
        String subject,
        String summary,
        Map<String, String> attributes,
        List<ImpactLocation> locations) {

    public Impact {
        attributes = Map.copyOf(attributes);
        locations =
                locations.stream()
                        .sorted(
                                Comparator.comparing(ImpactLocation::file)
                                        .thenComparingInt(ImpactLocation::line)
                                        .thenComparingInt(ImpactLocation::column))
                        .toList();
    }

    public Optional<Confidence> best() {
        return locations.stream().map(ImpactLocation::confidence).reduce(Confidence::best);
    }

    public boolean affectsMainCode() {
        return locations.stream().anyMatch(l -> !l.test());
    }

    public boolean isEmpty() {
        return locations.isEmpty();
    }
}
