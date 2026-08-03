package dev.rook.changes.openapi;

import dev.rook.changes.diff.DiffContext;
import dev.rook.core.change.ChangeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenApiDiffer {

    private static final Comparator<ChangeEvent> ORDER =
            Comparator.comparing(ChangeEvent::severity)
                    .reversed()
                    .thenComparing(e -> e.kind().name())
                    .thenComparing(e -> e.subject().name())
                    .thenComparing(ChangeEvent::fingerprint);

    private OpenApiDiffer() {}

    public static List<ChangeEvent> diff(
            String sourceId, OpenApiDocument before, OpenApiDocument after) {
        return diff(sourceId, before.version(), after.version(), before, after);
    }

    public static List<ChangeEvent> diff(
            String sourceId,
            String fromVersion,
            String toVersion,
            OpenApiDocument before,
            OpenApiDocument after) {
        DiffContext ctx = new DiffContext(sourceId, fromVersion, toVersion);
        List<ChangeEvent> all = new ArrayList<>(OperationDiff.run(ctx, before, after));
        all.addAll(SchemaDiff.run(ctx, before, after));
        Map<String, ChangeEvent> unique = new LinkedHashMap<>();
        for (ChangeEvent event : all) {
            unique.putIfAbsent(event.fingerprint(), event);
        }
        List<ChangeEvent> ordered = new ArrayList<>(unique.values());
        ordered.sort(ORDER);
        return List.copyOf(ordered);
    }
}
