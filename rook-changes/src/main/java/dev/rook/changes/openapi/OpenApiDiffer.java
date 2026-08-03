package dev.rook.changes.openapi;

import dev.rook.changes.diff.DiffContext;
import dev.rook.changes.diff.Events;
import dev.rook.core.change.ChangeEvent;
import java.util.ArrayList;
import java.util.List;

public final class OpenApiDiffer {

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
        return Events.normalize(all);
    }
}
