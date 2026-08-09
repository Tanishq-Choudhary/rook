package dev.rook.codeintel.impact;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.ImportInfo;
import dev.rook.codeintel.java.Model.Usage;
import dev.rook.codeintel.java.SymbolResolver;
import dev.rook.core.change.ChangeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class FieldMatcher {

    private final CodeIndex index;
    private final SymbolResolver resolver;

    FieldMatcher(CodeIndex index, SymbolResolver resolver) {
        this.index = index;
        this.resolver = resolver;
    }

    List<ImpactLocation> match(ChangeEvent event) {
        String name = event.subject().name();
        int hash = name.indexOf('#');
        if (hash < 0) {
            return List.of();
        }
        String owner = name.substring(0, hash);
        String field = name.substring(hash + 1);
        String ownerSimple = Matching.simpleName(owner);
        List<ImpactLocation> found = new ArrayList<>();
        for (FileIndex file : index.files()) {
            boolean visible = Matching.sees(file, owner);
            for (ImportInfo declaration : file.imports()) {
                if (declaration.isStatic() && declaration.name().equals(owner + "." + field)) {
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
                if (!usage.name().equals(field)) {
                    continue;
                }
                access(file, usage, owner, ownerSimple, visible).ifPresent(found::add);
            }
        }
        return found;
    }

    private Optional<ImpactLocation> access(
            FileIndex file, Usage usage, String owner, String ownerSimple, boolean visible) {
        switch (usage.kind()) {
            case FIELD_ACCESS, SWITCH_LABEL -> {
                Optional<SymbolResolver.Resolved> resolved =
                        resolver == null ? Optional.empty() : resolver.resolve(usage);
                if (resolved.isPresent()) {
                    return resolved.get().owner().equals(owner)
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
                    boolean named =
                            scopeType.equals(owner)
                                    || Matching.simpleName(scopeType).equals(ownerSimple);
                    return named && (visible || scopeType.equals(owner))
                            ? Optional.of(
                                    Matching.location(
                                            usage, Confidence.HEURISTIC, owner, file.test()))
                            : Optional.empty();
                }
                if (usage.kind() == dev.rook.codeintel.java.Model.UsageKind.SWITCH_LABEL
                        && visible) {
                    return Optional.of(
                            Matching.location(usage, Confidence.TEXTUAL, owner, file.test()));
                }
                return Optional.empty();
            }
            default -> {
                return Optional.empty();
            }
        }
    }
}
