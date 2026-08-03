package dev.rook.changes.jar;

import dev.rook.changes.diff.DiffContext;
import dev.rook.changes.diff.Events;
import dev.rook.core.change.ChangeEvent;
import java.util.List;

public final class ApiDiffer {

    private ApiDiffer() {}

    public static List<ChangeEvent> diff(
            String sourceId,
            String fromVersion,
            String toVersion,
            ApiSurface before,
            ApiSurface after) {
        DiffContext ctx = new DiffContext(sourceId, fromVersion, toVersion);
        return Events.normalize(new JarDiff(ctx, before, after).run());
    }
}
