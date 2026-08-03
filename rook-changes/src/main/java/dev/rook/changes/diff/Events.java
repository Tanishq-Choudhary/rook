package dev.rook.changes.diff;

import dev.rook.core.change.ChangeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Events {

    private static final Comparator<ChangeEvent> ORDER =
            Comparator.comparing(ChangeEvent::severity)
                    .reversed()
                    .thenComparing(e -> e.kind().name())
                    .thenComparing(e -> e.subject().name())
                    .thenComparing(ChangeEvent::fingerprint);

    private Events() {}

    public static List<ChangeEvent> normalize(List<ChangeEvent> events) {
        Map<String, ChangeEvent> unique = new LinkedHashMap<>();
        for (ChangeEvent event : events) {
            unique.putIfAbsent(event.fingerprint(), event);
        }
        List<ChangeEvent> ordered = new ArrayList<>(unique.values());
        ordered.sort(ORDER);
        return List.copyOf(ordered);
    }
}
