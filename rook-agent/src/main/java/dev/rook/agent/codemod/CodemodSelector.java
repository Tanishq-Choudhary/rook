package dev.rook.agent.codemod;

import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CodemodSelector {

    public record Planned(Codemod codemod, ChangeEvent event, List<ImpactLocation> locations) {}

    private CodemodSelector() {}

    public static List<Planned> select(List<ChangeEvent> events, ImpactReport report) {
        Map<String, ChangeEvent> byFingerprint = new HashMap<>();
        events.forEach(event -> byFingerprint.put(event.fingerprint(), event));
        List<Planned> planned = new ArrayList<>();
        for (Impact impact : report.impacts()) {
            ChangeEvent event = byFingerprint.get(impact.fingerprint());
            if (event == null || impact.locations().isEmpty()) {
                continue;
            }
            codemodFor(event)
                    .ifPresent(
                            codemod ->
                                    planned.add(new Planned(codemod, event, impact.locations())));
        }
        return planned;
    }

    static Optional<Codemod> codemodFor(ChangeEvent event) {
        String replacement = event.attribute(Attr.REPLACEMENT);
        try {
            return switch (event.kind()) {
                case METHOD_RENAMED -> {
                    String name = event.attribute(Attr.NAME);
                    yield name == null || replacement == null
                            ? Optional.empty()
                            : Optional.of(new RenameMethodCodemod(name, replacement));
                }
                case CLASS_MOVED -> {
                    String old = event.subject().name();
                    yield replacement == null
                            ? Optional.empty()
                            : Optional.of(new RenameTypeCodemod(old, replacement));
                }
                case ENUM_VALUE_REMOVED -> {
                    String old = event.attribute(Attr.VALUE);
                    String value = old == null ? leaf(event.subject().name()) : old;
                    yield replacement == null || value == null
                            ? Optional.empty()
                            : Optional.of(new ReplaceStringLiteralCodemod(value, replacement));
                }
                case FIELD_RENAMED -> {
                    String old = leaf(event.subject().name());
                    String now = replacement == null ? null : leaf(replacement);
                    yield old == null || now == null
                            ? Optional.empty()
                            : Optional.of(new ReplaceStringLiteralCodemod(old, now));
                }
                case METHOD_PARAMETERS_CHANGED -> removedArgument(event);
                default -> Optional.empty();
            };
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<Codemod> removedArgument(ChangeEvent event) {
        String name = event.attribute(Attr.NAME);
        String before = event.attribute(Attr.OLD_PARAMETERS);
        String after = event.attribute(Attr.NEW_PARAMETERS);
        if (name == null || before == null || after == null) {
            return Optional.empty();
        }
        List<String> old = split(before);
        List<String> now = split(after);
        if (old.size() != now.size() + 1) {
            return Optional.empty();
        }
        int found = -1;
        for (int i = 0; i < old.size(); i++) {
            List<String> without = new ArrayList<>(old);
            without.remove(i);
            if (without.equals(now)) {
                if (found >= 0) {
                    return Optional.empty();
                }
                found = i;
            }
        }
        return found < 0 ? Optional.empty() : Optional.of(new DropArgumentCodemod(name, found));
    }

    private static List<String> split(String parameters) {
        return parameters.isBlank() ? List.of() : List.of(parameters.split(","));
    }

    private static String leaf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int cut = -1;
        for (char separator : new char[] {'.', '/', '=', '#'}) {
            cut = Math.max(cut, path.lastIndexOf(separator));
        }
        String name = path.substring(cut + 1);
        return name.isBlank() ? null : name;
    }
}
