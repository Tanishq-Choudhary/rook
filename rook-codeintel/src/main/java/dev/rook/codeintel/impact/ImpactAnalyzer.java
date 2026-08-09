package dev.rook.codeintel.impact;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.SymbolResolver;
import dev.rook.codeintel.text.TextIndex;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ImpactAnalyzer {

    public static final int MAX_LOCATIONS_PER_EVENT = 500;

    private final MethodMatcher methods;
    private final ClassMatcher classes;
    private final FieldMatcher fields;
    private final OpenApiMatcher openApi;

    private ImpactAnalyzer(CodeIndex index, SymbolResolver resolver, TextIndex text) {
        this.methods = new MethodMatcher(index, resolver);
        this.classes = new ClassMatcher(index, resolver);
        this.fields = new FieldMatcher(index, resolver);
        this.openApi = new OpenApiMatcher(index, resolver, text);
    }

    public static ImpactAnalyzer create(CodeIndex index, SymbolResolver resolver) {
        return new ImpactAnalyzer(index, resolver, TextIndex.build(index.root()));
    }

    public static ImpactAnalyzer create(CodeIndex index, SymbolResolver resolver, TextIndex text) {
        return new ImpactAnalyzer(index, resolver, text);
    }

    public ImpactReport analyze(List<ChangeEvent> events) {
        List<Impact> impacts = new ArrayList<>();
        for (ChangeEvent event : events) {
            if (!relevant(event)) {
                continue;
            }
            impacts.add(impactOf(event, locate(event)));
        }
        return ImpactReport.of(impacts);
    }

    private static boolean relevant(ChangeEvent event) {
        return event.severity().atLeast(Severity.DEPRECATION) && !event.kind().isAdvisory();
    }

    private List<ImpactLocation> locate(ChangeEvent event) {
        return switch (event.subject().kind()) {
            case JAVA_METHOD -> methods.match(event);
            case JAVA_CLASS -> classes.match(event);
            case JAVA_FIELD -> fields.match(event);
            case OPERATION, PARAMETER, SCHEMA_FIELD, ENUM_VALUE -> openApi.match(event);
            default -> List.of();
        };
    }

    private static Impact impactOf(ChangeEvent event, List<ImpactLocation> found) {
        Map<String, ImpactLocation> unique = new LinkedHashMap<>();
        for (ImpactLocation location : found) {
            unique.merge(
                    location.key(),
                    location,
                    (a, b) -> a.confidence().compareTo(b.confidence()) >= 0 ? a : b);
            if (unique.size() >= MAX_LOCATIONS_PER_EVENT) {
                break;
            }
        }
        return new Impact(
                event.fingerprint(),
                event.kind(),
                event.severity(),
                event.subject().name(),
                event.summary(),
                event.attributes(),
                new ArrayList<>(unique.values()));
    }
}
