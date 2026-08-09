package dev.rook.codeintel.impact;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.ImportInfo;
import dev.rook.codeintel.java.Model.TypeInfo;
import dev.rook.codeintel.java.Model.Usage;
import dev.rook.codeintel.java.Model.UsageKind;
import dev.rook.codeintel.java.SymbolResolver;
import dev.rook.core.change.ChangeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ClassMatcher {

    private final CodeIndex index;
    private final SymbolResolver resolver;

    ClassMatcher(CodeIndex index, SymbolResolver resolver) {
        this.index = index;
        this.resolver = resolver;
    }

    List<ImpactLocation> match(ChangeEvent event) {
        String owner = event.subject().name();
        String simple = Matching.simpleName(owner);
        List<ImpactLocation> found = new ArrayList<>();
        for (FileIndex file : index.files()) {
            boolean visible = Matching.sees(file, owner);
            for (ImportInfo declaration : file.imports()) {
                if (!declaration.isStatic() && declaration.name().equals(owner)) {
                    found.add(
                            Matching.declaration(
                                    file,
                                    declaration.line(),
                                    "IMPORT",
                                    Confidence.RESOLVED,
                                    owner,
                                    ""));
                } else if (declaration.isStatic() && declaration.name().startsWith(owner + ".")) {
                    found.add(
                            Matching.declaration(
                                    file,
                                    declaration.line(),
                                    "IMPORT_STATIC",
                                    Confidence.RESOLVED,
                                    declaration.name(),
                                    ""));
                }
            }
            for (Usage usage : file.usages()) {
                typeReference(file, usage, owner, simple, visible).ifPresent(found::add);
            }
            for (TypeInfo type : file.types()) {
                if (type.qualifiedName().equals(owner)) {
                    continue;
                }
                boolean extendsOwner =
                        type.superTypes().stream()
                                .anyMatch(
                                        s ->
                                                s.equals(owner)
                                                        || (Matching.simpleName(s).equals(simple)
                                                                && visible));
                if (extendsOwner) {
                    found.add(
                            Matching.declaration(
                                    file,
                                    type.line(),
                                    "EXTENDS",
                                    Confidence.HEURISTIC,
                                    owner,
                                    type.qualifiedName()));
                }
            }
        }
        return found;
    }

    private Optional<ImpactLocation> typeReference(
            FileIndex file, Usage usage, String owner, String simple, boolean visible) {
        switch (usage.kind()) {
            case TYPE_USE, CONSTRUCTOR_CALL -> {
                if (usage.name().equals(owner)) {
                    return Optional.of(
                            Matching.location(usage, Confidence.RESOLVED, owner, file.test()));
                }
                if (!visible || !Matching.simpleName(usage.name()).equals(simple)) {
                    return Optional.empty();
                }
                Optional<SymbolResolver.Resolved> resolved =
                        resolver == null || usage.kind() == UsageKind.CONSTRUCTOR_CALL
                                ? Optional.empty()
                                : resolver.resolve(usage);
                if (resolved.isPresent()) {
                    return resolved.get().owner().equals(owner)
                            ? Optional.of(
                                    Matching.location(
                                            usage, Confidence.RESOLVED, owner, file.test()))
                            : Optional.empty();
                }
                return Optional.of(
                        Matching.location(usage, Confidence.HEURISTIC, owner, file.test()));
            }
            case METHOD_CALL, FIELD_ACCESS, METHOD_REFERENCE -> {
                String scopeType = usage.scopeType();
                if (scopeType != null
                        && visible
                        && (scopeType.equals(owner)
                                || Matching.simpleName(scopeType).equals(simple))
                        && usage.scope() != null
                        && Character.isUpperCase(usage.scope().charAt(0))) {
                    return Optional.of(
                            Matching.location(usage, Confidence.HEURISTIC, owner, file.test()));
                }
                return Optional.empty();
            }
            default -> {
                return Optional.empty();
            }
        }
    }
}
