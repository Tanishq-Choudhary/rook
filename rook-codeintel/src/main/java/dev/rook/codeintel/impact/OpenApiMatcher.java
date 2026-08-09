package dev.rook.codeintel.impact;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.Model.FieldInfo;
import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.TypeInfo;
import dev.rook.codeintel.java.Model.Usage;
import dev.rook.codeintel.java.Model.UsageKind;
import dev.rook.codeintel.java.SymbolResolver;
import dev.rook.codeintel.text.TextIndex;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class OpenApiMatcher {

    private static final Set<String> DTO_SUFFIXES =
            Set.of(
                    "Dto",
                    "DTO",
                    "Response",
                    "Request",
                    "Model",
                    "Entity",
                    "Payload",
                    "Resource",
                    "Params");
    private static final Set<String> PARAM_ANNOTATIONS =
            Set.of(
                    "Query",
                    "RequestParam",
                    "QueryParam",
                    "Param",
                    "Header",
                    "RequestHeader",
                    "HeaderParam");
    private static final Map<String, String> VERB_ANNOTATIONS =
            Map.of(
                    "GetMapping",
                    "GET",
                    "GET",
                    "GET",
                    "PostMapping",
                    "POST",
                    "POST",
                    "POST",
                    "PutMapping",
                    "PUT",
                    "PUT",
                    "PUT",
                    "DeleteMapping",
                    "DELETE",
                    "DELETE",
                    "DELETE",
                    "PatchMapping",
                    "PATCH",
                    "PATCH",
                    "PATCH");

    private final CodeIndex index;
    private final SymbolResolver resolver;
    private final TextIndex text;

    OpenApiMatcher(CodeIndex index, SymbolResolver resolver, TextIndex text) {
        this.index = index;
        this.resolver = resolver;
        this.text = text;
    }

    List<ImpactLocation> match(ChangeEvent event) {
        return switch (event.subject().kind()) {
            case OPERATION -> operation(event);
            case PARAMETER -> parameter(event);
            case SCHEMA_FIELD -> schemaField(event);
            case ENUM_VALUE -> enumValue(event);
            default -> List.of();
        };
    }

    private List<ImpactLocation> operation(ChangeEvent event) {
        String name = event.subject().name();
        int space = name.indexOf(' ');
        if (space < 0) {
            return List.of();
        }
        String verb = name.substring(0, space);
        PathTemplate path = new PathTemplate(name.substring(space + 1));
        String operationId = event.attribute(Attr.OPERATION_ID);
        List<ImpactLocation> found = new ArrayList<>();
        for (FileIndex file : index.files()) {
            Set<Integer> annotatedLines = new HashSet<>();
            for (Usage usage : file.usages()) {
                if (usage.kind() == UsageKind.ANNOTATION) {
                    String annotationVerb = VERB_ANNOTATIONS.get(usage.name());
                    boolean verbOk = annotationVerb == null || annotationVerb.equals(verb);
                    boolean pathOk = usage.values().stream().anyMatch(path::matches);
                    if (pathOk) {
                        annotatedLines.add(usage.location().line());
                    }
                    if (pathOk && verbOk) {
                        found.add(
                                Matching.location(usage, Confidence.HEURISTIC, name, file.test()));
                    }
                }
            }
            for (Usage usage : file.usages()) {
                if (usage.kind() == UsageKind.STRING_LITERAL
                        && !annotatedLines.contains(usage.location().line())) {
                    if (path.matches(usage.name())) {
                        found.add(
                                retag(
                                        Matching.location(
                                                usage, Confidence.HEURISTIC, name, file.test()),
                                        "ENDPOINT_PATH"));
                    } else if (path.matchesPrefix(usage.name())
                            || path.matchesWithoutVersion(usage.name())) {
                        found.add(
                                retag(
                                        Matching.location(
                                                usage, Confidence.TEXTUAL, name, file.test()),
                                        "ENDPOINT_PATH"));
                    }
                }
                if (operationId != null
                        && usage.kind() == UsageKind.METHOD_CALL
                        && usage.name().equals(operationId)) {
                    found.add(operationCall(file, usage, operationId));
                }
            }
            if (operationId != null) {
                for (TypeInfo type : file.types()) {
                    type.methods().stream()
                            .filter(m -> m.name().equals(operationId))
                            .forEach(
                                    m ->
                                            found.add(
                                                    Matching.declaration(
                                                            file,
                                                            m.line(),
                                                            "METHOD_DECLARATION",
                                                            Confidence.HEURISTIC,
                                                            name,
                                                            type.qualifiedName()
                                                                    + "#"
                                                                    + m.name())));
                }
            }
        }
        for (TextIndex.Match match : text.findValue(name.substring(space + 1))) {
            found.add(resource(match, name));
        }
        return found;
    }

    private ImpactLocation operationCall(FileIndex file, Usage usage, String operationId) {
        boolean declared =
                usage.scopeType() != null
                        && index.typesBySimpleName(Matching.simpleName(usage.scopeType())).stream()
                                .anyMatch(
                                        t ->
                                                t.methods().stream()
                                                        .anyMatch(
                                                                m -> m.name().equals(operationId)));
        return Matching.location(
                usage,
                declared ? Confidence.HEURISTIC : Confidence.TEXTUAL,
                operationId,
                file.test());
    }

    private List<ImpactLocation> parameter(ChangeEvent event) {
        String subject = event.subject().name();
        int space = subject.lastIndexOf(' ');
        String name = event.attribute(Attr.NAME);
        String location = event.attribute(Attr.LOCATION);
        if (space < 0 || name == null || "body".equals(location) || "path".equals(location)) {
            return List.of();
        }
        String operation = subject.substring(0, space);
        int verbEnd = operation.indexOf(' ');
        PathTemplate path = new PathTemplate(operation.substring(verbEnd + 1));
        List<ImpactLocation> found = new ArrayList<>();
        for (FileIndex file : index.files()) {
            boolean hasPath =
                    file.usages().stream()
                            .anyMatch(
                                    u ->
                                            (u.kind() == UsageKind.STRING_LITERAL
                                                            && path.matches(u.name()))
                                                    || (u.kind() == UsageKind.ANNOTATION
                                                            && u.values().stream()
                                                                    .anyMatch(path::matches)));
            for (Usage usage : file.usages()) {
                boolean header = "header".equals(location);
                if (usage.kind() == UsageKind.ANNOTATION
                        && PARAM_ANNOTATIONS.contains(usage.name())
                        && usage.values().stream().anyMatch(v -> sameName(v, name, header))) {
                    found.add(
                            Matching.location(
                                    usage,
                                    hasPath ? Confidence.HEURISTIC : Confidence.TEXTUAL,
                                    subject,
                                    file.test()));
                } else if (usage.kind() == UsageKind.STRING_LITERAL) {
                    boolean exact = sameName(usage.name(), name, header);
                    boolean keyed =
                            usage.name().contains(name + "=") || usage.name().contains("?" + name);
                    if ((exact || keyed) && hasPath) {
                        found.add(
                                Matching.location(
                                        usage, Confidence.HEURISTIC, subject, file.test()));
                    } else if (exact && name.length() >= 4 && !hasPath) {
                        found.add(
                                Matching.location(usage, Confidence.TEXTUAL, subject, file.test()));
                    }
                }
            }
        }
        return found;
    }

    private List<ImpactLocation> schemaField(ChangeEvent event) {
        String schema = event.attribute(Attr.SCHEMA);
        String field = event.attribute(Attr.FIELD);
        if (schema == null || field == null) {
            return List.of();
        }
        String leaf = leaf(field);
        if (schema.contains(" ")) {
            return inlineBodyField(event, schema, leaf);
        }
        List<TypeInfo> dtos = dtoCandidates(schema, field);
        List<ImpactLocation> found = new ArrayList<>();
        for (TypeInfo type : dtos) {
            FileIndex file = index.file(type.file()).orElse(null);
            if (file == null) {
                continue;
            }
            Optional<FieldInfo> declared = matchingField(type, leaf);
            if (declared.isPresent()) {
                FieldInfo f = declared.get();
                boolean explicit = f.annotationValues().contains(leaf);
                found.add(
                        Matching.declaration(
                                file,
                                f.line(),
                                "DTO_FIELD",
                                explicit ? Confidence.RESOLVED : Confidence.HEURISTIC,
                                type.qualifiedName() + "#" + f.name(),
                                type.qualifiedName()));
                accessorUsages(type, f.name(), leaf, found);
            } else if (adds(event.kind())) {
                found.add(
                        Matching.declaration(
                                file,
                                type.line(),
                                "DTO_TYPE",
                                Confidence.HEURISTIC,
                                type.qualifiedName(),
                                type.qualifiedName()));
                constructionUsages(type, found);
            }
            if (type.kind().equals("RECORD") && declared.isPresent()) {
                constructionUsages(type, found);
            }
        }
        jsonNameUsages(leaf, dtos, found);
        return found;
    }

    private List<ImpactLocation> inlineBodyField(ChangeEvent event, String schema, String leaf) {
        int split = schema.indexOf(" request");
        if (split < 0) {
            split = schema.indexOf(" response");
        }
        if (split < 0) {
            return List.of();
        }
        String operation = schema.substring(0, split);
        int verbEnd = operation.indexOf(' ');
        if (verbEnd < 0) {
            return List.of();
        }
        PathTemplate path = new PathTemplate(operation.substring(verbEnd + 1));
        List<ImpactLocation> found = new ArrayList<>();
        for (FileIndex file : index.files()) {
            boolean hasPath =
                    file.usages().stream()
                            .anyMatch(
                                    u ->
                                            u.kind() == UsageKind.STRING_LITERAL
                                                    && path.matches(u.name()));
            if (!hasPath) {
                continue;
            }
            for (Usage usage : file.usages()) {
                if (usage.kind() == UsageKind.STRING_LITERAL && usage.name().equals(leaf)) {
                    found.add(
                            Matching.location(
                                    usage, Confidence.HEURISTIC, schema + "." + leaf, file.test()));
                }
            }
        }
        return found;
    }

    private void accessorUsages(
            TypeInfo type, String fieldName, String jsonName, List<ImpactLocation> found) {
        Set<String> names = new HashSet<>(NameForms.accessorNames(fieldName));
        names.addAll(NameForms.accessorNames(jsonName));
        for (FileIndex file : index.files()) {
            boolean visible = Matching.sees(file, type.qualifiedName());
            for (Usage usage : file.usages()) {
                if (usage.kind() == UsageKind.METHOD_CALL
                        && names.contains(usage.name())
                        && usage.argCount() <= 1) {
                    classify(file, usage, type, visible).ifPresent(found::add);
                } else if (usage.kind() == UsageKind.FIELD_ACCESS
                        && usage.name().equals(fieldName)
                        && usage.scopeType() != null
                        && Matching.simpleName(usage.scopeType()).equals(type.simpleName())) {
                    found.add(
                            Matching.location(
                                    usage,
                                    Confidence.HEURISTIC,
                                    type.qualifiedName(),
                                    file.test()));
                }
            }
        }
    }

    private Optional<ImpactLocation> classify(
            FileIndex file, Usage usage, TypeInfo type, boolean visible) {
        Optional<SymbolResolver.Resolved> resolved =
                resolver == null ? Optional.empty() : resolver.resolve(usage);
        if (resolved.isPresent()) {
            return resolved.get().owner().equals(type.qualifiedName())
                    ? Optional.of(
                            Matching.location(
                                    usage,
                                    Confidence.RESOLVED,
                                    resolved.get().signature(),
                                    file.test()))
                    : Optional.empty();
        }
        String scopeType = usage.scopeType();
        if (scopeType != null) {
            return Matching.simpleName(scopeType).equals(type.simpleName())
                    ? Optional.of(
                            Matching.location(
                                    usage, Confidence.HEURISTIC, type.qualifiedName(), file.test()))
                    : Optional.empty();
        }
        return visible
                ? Optional.of(
                        Matching.location(
                                usage, Confidence.TEXTUAL, type.qualifiedName(), file.test()))
                : Optional.empty();
    }

    private void constructionUsages(TypeInfo type, List<ImpactLocation> found) {
        for (FileIndex file : index.files()) {
            for (Usage usage : file.usages()) {
                boolean creation =
                        usage.kind() == UsageKind.CONSTRUCTOR_CALL
                                && Matching.simpleName(usage.name()).equals(type.simpleName());
                boolean builder =
                        usage.kind() == UsageKind.METHOD_CALL
                                && usage.name().equals("builder")
                                && usage.scopeType() != null
                                && Matching.simpleName(usage.scopeType()).equals(type.simpleName());
                if (creation || builder) {
                    found.add(
                            Matching.location(
                                    usage,
                                    Confidence.HEURISTIC,
                                    type.qualifiedName(),
                                    file.test()));
                }
            }
        }
    }

    private void jsonNameUsages(String jsonName, List<TypeInfo> dtos, List<ImpactLocation> found) {
        Set<String> dtoNames = new HashSet<>();
        dtos.forEach(t -> dtoNames.add(t.simpleName()));
        for (FileIndex file : index.files()) {
            boolean related =
                    file.usages().stream()
                            .anyMatch(
                                    u ->
                                            (u.kind() == UsageKind.TYPE_USE
                                                            || u.kind()
                                                                    == UsageKind.CONSTRUCTOR_CALL)
                                                    && dtoNames.contains(
                                                            Matching.simpleName(u.name())));
            for (Usage usage : file.usages()) {
                if (usage.kind() == UsageKind.STRING_LITERAL && usage.name().equals(jsonName)) {
                    found.add(
                            retag(
                                    Matching.location(
                                            usage,
                                            related ? Confidence.HEURISTIC : Confidence.TEXTUAL,
                                            jsonName,
                                            file.test()),
                                    "JSON_NAME"));
                }
            }
        }
        for (TextIndex.Match match : text.findKey(jsonName)) {
            found.add(resource(match, jsonName));
        }
    }

    private List<ImpactLocation> enumValue(ChangeEvent event) {
        String subject = event.subject().name();
        int equals = subject.lastIndexOf('=');
        if (equals < 0) {
            return List.of();
        }
        String owner = subject.substring(0, equals);
        String value = subject.substring(equals + 1);
        String constant = NameForms.upperSnake(value);
        Set<String> ownerWords = new HashSet<>(NameForms.words(owner.replace('.', '_')));
        List<ImpactLocation> found = new ArrayList<>();
        List<TypeInfo> enums =
                index.types().values().stream().filter(t -> t.kind().equals("ENUM")).toList();
        List<TypeInfo> strong = new ArrayList<>();
        for (TypeInfo type : enums) {
            boolean hasConstant =
                    type.fields().stream()
                            .anyMatch(f -> f.enumConstant() && f.name().equals(constant));
            boolean related =
                    new HashSet<>(NameForms.words(type.simpleName())).containsAll(ownerWords);
            if (hasConstant && related) {
                strong.add(type);
                FileIndex file = index.file(type.file()).orElseThrow();
                type.fields().stream()
                        .filter(f -> f.enumConstant() && f.name().equals(constant))
                        .forEach(
                                f ->
                                        found.add(
                                                Matching.declaration(
                                                        file,
                                                        f.line(),
                                                        "ENUM_CONSTANT",
                                                        Confidence.HEURISTIC,
                                                        type.qualifiedName() + "#" + constant,
                                                        type.qualifiedName())));
            }
        }
        for (FileIndex file : index.files()) {
            for (Usage usage : file.usages()) {
                if ((usage.kind() == UsageKind.FIELD_ACCESS
                                || usage.kind() == UsageKind.SWITCH_LABEL)
                        && usage.name().equals(constant)) {
                    enumUsage(file, usage, strong).ifPresent(found::add);
                } else if (usage.kind() == UsageKind.STRING_LITERAL && usage.name().equals(value)) {
                    boolean related =
                            file.usages().stream()
                                    .anyMatch(
                                            u ->
                                                    (u.kind() == UsageKind.TYPE_USE
                                                                    || u.kind()
                                                                            == UsageKind
                                                                                    .CONSTRUCTOR_CALL)
                                                            && ownerWords.contains(
                                                                    NameForms.words(
                                                                                    Matching
                                                                                            .simpleName(
                                                                                                    u
                                                                                                            .name()))
                                                                            .stream()
                                                                            .findFirst()
                                                                            .orElse("")));
                    found.add(
                            retag(
                                    Matching.location(
                                            usage,
                                            related ? Confidence.HEURISTIC : Confidence.TEXTUAL,
                                            subject,
                                            file.test()),
                                    "ENUM_LITERAL"));
                }
            }
        }
        for (TextIndex.Match match : text.findValue(value)) {
            found.add(resource(match, subject));
        }
        return found;
    }

    private Optional<ImpactLocation> enumUsage(FileIndex file, Usage usage, List<TypeInfo> strong) {
        if (resolver != null) {
            Optional<SymbolResolver.Resolved> resolved = resolver.resolve(usage);
            if (resolved.isPresent()) {
                boolean match =
                        strong.stream()
                                .anyMatch(t -> t.qualifiedName().equals(resolved.get().owner()));
                return match
                        ? Optional.of(
                                Matching.location(
                                        usage,
                                        Confidence.RESOLVED,
                                        resolved.get().signature(),
                                        file.test()))
                        : Optional.empty();
            }
        }
        for (TypeInfo type : strong) {
            boolean named =
                    usage.scopeType() != null
                            && Matching.simpleName(usage.scopeType()).equals(type.simpleName());
            boolean switchLabel =
                    usage.kind() == UsageKind.SWITCH_LABEL
                            && Matching.sees(file, type.qualifiedName());
            if (named || switchLabel) {
                return Optional.of(
                        Matching.location(
                                usage, Confidence.HEURISTIC, type.qualifiedName(), file.test()));
            }
        }
        return Optional.empty();
    }

    private List<TypeInfo> dtoCandidates(String schema, String fieldPath) {
        Set<String> names = new HashSet<>();
        names.add(schema);
        String[] segments = fieldPath.replace("[]", "").split("\\.");
        for (int i = 0; i < segments.length - 1; i++) {
            names.add(NameForms.pascal(segments[i]));
        }
        List<TypeInfo> found = new ArrayList<>();
        for (TypeInfo type : index.types().values()) {
            String simple = type.simpleName();
            boolean exact = names.contains(simple);
            boolean suffixed =
                    names.stream()
                            .anyMatch(
                                    n ->
                                            simple.startsWith(n)
                                                    && DTO_SUFFIXES.contains(
                                                            simple.substring(n.length())));
            if (exact || suffixed) {
                found.add(type);
            }
        }
        return found;
    }

    private static Optional<FieldInfo> matchingField(TypeInfo type, String jsonName) {
        for (FieldInfo field : type.fields()) {
            if (field.annotationValues().contains(jsonName)) {
                return Optional.of(field);
            }
        }
        for (FieldInfo field : type.fields()) {
            if (!field.enumConstant() && NameForms.sameWords(field.name(), jsonName)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    private static boolean adds(ChangeKind kind) {
        return kind == ChangeKind.REQUEST_FIELD_REQUIRED_ADDED;
    }

    private static boolean sameName(String candidate, String name, boolean ignoreCase) {
        return ignoreCase
                ? candidate.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))
                : candidate.equals(name);
    }

    private static String leaf(String fieldPath) {
        String cleaned = fieldPath.replace("[]", "");
        int dot = cleaned.lastIndexOf('.');
        return dot < 0 ? cleaned : cleaned.substring(dot + 1);
    }

    private static ImpactLocation retag(ImpactLocation location, String kind) {
        return new ImpactLocation(
                location.file(),
                location.line(),
                location.column(),
                location.endLine(),
                location.snippet(),
                location.enclosing(),
                kind,
                location.confidence(),
                location.resolvedTarget(),
                location.test());
    }

    private static ImpactLocation resource(TextIndex.Match match, String target) {
        return new ImpactLocation(
                match.file(),
                match.line(),
                0,
                match.line(),
                match.snippet(),
                "",
                "RESOURCE",
                Confidence.TEXTUAL,
                target,
                match.test());
    }
}
