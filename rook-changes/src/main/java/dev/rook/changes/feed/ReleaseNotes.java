package dev.rook.changes.feed;

import dev.rook.changes.diff.DiffContext;
import dev.rook.changes.diff.Events;
import dev.rook.changes.feed.ChangelogClassifier.Category;
import dev.rook.changes.feed.ChangelogClassifier.Finding;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.MigrationNote;
import dev.rook.core.change.SymbolKind;
import dev.rook.core.change.SymbolRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class ReleaseNotes {

    private static final int EXCERPT_LIMIT = 400;

    private ReleaseNotes() {}

    public record Result(
            List<ChangeEvent> events, List<MigrationNote> notes, List<Finding> findings) {}

    public static Result fromEntry(String sourceId, String fromVersion, ReleaseEntry entry) {
        DiffContext ctx = new DiffContext(sourceId, fromVersion, entry.version());
        List<Finding> findings = ChangelogClassifier.classify(entry.body());
        List<ChangeEvent> events = new ArrayList<>();
        List<MigrationNote> notes = new ArrayList<>();
        for (Finding finding : findings) {
            ChangeKind kind =
                    finding.category() == Category.DEPRECATION
                            ? ChangeKind.ADVISORY_DEPRECATION
                            : ChangeKind.ADVISORY_BREAKING;
            Map<String, String> attributes = new TreeMap<>();
            attributes.put("excerpt", excerpt(finding.text()));
            attributes.put("category", finding.category().name().toLowerCase(Locale.ROOT));
            if (finding.from() != null) {
                attributes.put(Attr.FROM, finding.from());
            }
            if (finding.replacement() != null) {
                attributes.put(Attr.REPLACEMENT, finding.replacement());
            }
            String subject =
                    finding.from() != null
                            ? finding.from()
                            : !finding.symbols().isEmpty()
                                    ? finding.symbols().get(0)
                                    : excerpt(finding.text());
            events.add(
                    ctx.event(
                            kind,
                            SymbolRef.advisory(subject),
                            attributes,
                            excerpt(finding.text())));
            notes.add(new MigrationNote(entry.title(), finding.text(), entry.url()));
        }
        return new Result(Events.normalize(events), List.copyOf(notes), findings);
    }

    public static List<ChangeEvent> enrich(
            DiffContext ctx, List<ChangeEvent> events, List<Finding> findings) {
        List<ChangeEvent> enriched = new ArrayList<>();
        for (ChangeEvent event : events) {
            if (event.attribute(Attr.REPLACEMENT) != null || event.kind().isAdvisory()) {
                enriched.add(event);
                continue;
            }
            Finding match = findMatch(event, findings);
            if (match == null || match.replacement() == null) {
                enriched.add(event);
                continue;
            }
            Map<String, String> attributes = new TreeMap<>(event.attributes());
            attributes.put(Attr.REPLACEMENT, match.replacement());
            attributes.put("replacementSource", "release-notes");
            enriched.add(
                    new ChangeEvent(
                            ChangeEvent.fingerprint(
                                    ctx.sourceId(),
                                    ctx.fromVersion(),
                                    ctx.toVersion(),
                                    event.kind(),
                                    event.subject(),
                                    attributes),
                            event.kind(),
                            event.severity(),
                            event.subject(),
                            attributes,
                            event.summary() + " (notes suggest " + match.replacement() + ")"));
        }
        return List.copyOf(enriched);
    }

    private static Finding findMatch(ChangeEvent event, List<Finding> findings) {
        String member = memberOf(event);
        String owner = ownerOf(event);
        if (member == null) {
            return null;
        }
        for (Finding finding : findings) {
            if (finding.from() == null) {
                continue;
            }
            String symbol = finding.from().replaceAll("\\(.*\\)", "").strip();
            String[] parts = symbol.split("[.#]");
            String last = parts[parts.length - 1];
            if (!last.equals(member)) {
                continue;
            }
            if (parts.length == 1 || owner == null || owner.equals(parts[parts.length - 2])) {
                return finding;
            }
        }
        return null;
    }

    private static String memberOf(ChangeEvent event) {
        SymbolRef subject = event.subject();
        return switch (subject.kind()) {
            case JAVA_METHOD ->
                    subject.asMethod().map(SymbolRef.MethodSignature::name).orElse(null);
            case JAVA_FIELD -> subject.name().substring(subject.name().indexOf('#') + 1);
            case JAVA_CLASS -> simpleName(subject.name());
            case SCHEMA_FIELD -> {
                String name = subject.name();
                yield name.substring(name.lastIndexOf('.') + 1);
            }
            case OPERATION -> event.attribute(Attr.OPERATION_ID);
            case PARAMETER -> event.attribute(Attr.NAME);
            case ENUM_VALUE -> event.attribute(Attr.VALUE);
            default -> null;
        };
    }

    private static String ownerOf(ChangeEvent event) {
        SymbolRef subject = event.subject();
        if (subject.kind() == SymbolKind.SCHEMA_FIELD) {
            String name = subject.name();
            int dot = name.indexOf('.');
            return dot < 0 ? null : name.substring(0, dot);
        }
        return subject.ownerType().map(ReleaseNotes::simpleName).orElse(null);
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static String excerpt(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() <= EXCERPT_LIMIT ? single : single.substring(0, EXCERPT_LIMIT);
    }
}
