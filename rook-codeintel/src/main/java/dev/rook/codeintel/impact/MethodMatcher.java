package dev.rook.codeintel.impact;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.MethodInfo;
import dev.rook.codeintel.java.Model.TypeInfo;
import dev.rook.codeintel.java.Model.Usage;
import dev.rook.codeintel.java.Model.UsageKind;
import dev.rook.codeintel.java.SymbolResolver;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.SymbolRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class MethodMatcher {

    private final CodeIndex index;
    private final SymbolResolver resolver;

    MethodMatcher(CodeIndex index, SymbolResolver resolver) {
        this.index = index;
        this.resolver = resolver;
    }

    List<ImpactLocation> match(ChangeEvent event) {
        Optional<SymbolRef.MethodSignature> parsed = event.subject().asMethod();
        if (parsed.isEmpty()) {
            return List.of();
        }
        SymbolRef.MethodSignature signature = parsed.get();
        boolean constructor = "<init>".equals(signature.name());
        List<ImpactLocation> found = new ArrayList<>();
        for (FileIndex file : index.files()) {
            for (Usage usage : file.usages()) {
                if (constructor) {
                    matchConstructor(file, usage, signature).ifPresent(found::add);
                } else {
                    matchCall(file, usage, signature).ifPresent(found::add);
                }
            }
            if (!constructor) {
                found.addAll(overrides(file, signature));
            }
        }
        return found;
    }

    private Optional<ImpactLocation> matchCall(
            FileIndex file, Usage usage, SymbolRef.MethodSignature signature) {
        boolean call = usage.kind() == UsageKind.METHOD_CALL;
        boolean reference = usage.kind() == UsageKind.METHOD_REFERENCE;
        if (!(call || reference) || !usage.name().equals(signature.name())) {
            return Optional.empty();
        }
        if (call && usage.argCount() != signature.parameterTypes().size()) {
            return Optional.empty();
        }
        String owner = signature.owner();
        String ownerSimple = Matching.simpleName(owner);
        Optional<SymbolResolver.Resolved> resolved =
                resolver == null ? Optional.empty() : resolver.resolve(usage);
        if (resolved.isPresent()) {
            SymbolResolver.Resolved target = resolved.get();
            if (target.owner().equals(owner)
                    && (reference
                            || Matching.sameParameters(
                                    target.signature(), signature.parameterTypes()))) {
                return Optional.of(
                        Matching.location(
                                usage, Confidence.RESOLVED, target.signature(), file.test()));
            }
            boolean overriddenInRepository =
                    target.member().equals(signature.name())
                            && index.type(target.owner())
                                    .map(
                                            type ->
                                                    Matching.extendsType(
                                                            index, type, ownerSimple, owner))
                                    .orElse(false);
            return overriddenInRepository
                    ? Optional.of(
                            Matching.location(
                                    usage, Confidence.RESOLVED, target.signature(), file.test()))
                    : Optional.empty();
        }
        String scopeType = usage.scopeType();
        if (scopeType != null) {
            boolean named =
                    Matching.simpleName(scopeType).equals(ownerSimple) || scopeType.equals(owner);
            if (named && (Matching.sees(file, owner) || scopeType.equals(owner))) {
                return Optional.of(
                        Matching.location(usage, Confidence.HEURISTIC, owner, file.test()));
            }
            if (Matching.isRepoSubtype(index, scopeType, ownerSimple, owner)) {
                return Optional.of(
                        Matching.location(usage, Confidence.HEURISTIC, owner, file.test()));
            }
            return Optional.empty();
        }
        if (file.imports(owner)) {
            return Optional.of(Matching.location(usage, Confidence.TEXTUAL, owner, file.test()));
        }
        return Optional.empty();
    }

    private Optional<ImpactLocation> matchConstructor(
            FileIndex file, Usage usage, SymbolRef.MethodSignature signature) {
        if (usage.kind() != UsageKind.CONSTRUCTOR_CALL
                || usage.argCount() != signature.parameterTypes().size()) {
            return Optional.empty();
        }
        String owner = signature.owner();
        String ownerSimple = Matching.simpleName(owner);
        boolean named =
                usage.name().equals(owner) || Matching.simpleName(usage.name()).equals(ownerSimple);
        if (!named) {
            return Optional.empty();
        }
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
        if (usage.name().equals(owner) || Matching.sees(file, owner)) {
            return Optional.of(Matching.location(usage, Confidence.HEURISTIC, owner, file.test()));
        }
        return Optional.empty();
    }

    private List<ImpactLocation> overrides(FileIndex file, SymbolRef.MethodSignature signature) {
        List<ImpactLocation> found = new ArrayList<>();
        String owner = signature.owner();
        String ownerSimple = Matching.simpleName(owner);
        for (TypeInfo type : file.types()) {
            if (type.qualifiedName().equals(owner)) {
                continue;
            }
            if (!Matching.extendsType(index, type, ownerSimple, owner)) {
                continue;
            }
            for (MethodInfo method : type.methods()) {
                if (method.name().equals(signature.name())
                        && method.parameterTypes().size() == signature.parameterTypes().size()) {
                    found.add(
                            Matching.declaration(
                                    file,
                                    method.line(),
                                    "OVERRIDE",
                                    Confidence.HEURISTIC,
                                    owner + "#" + signature.name(),
                                    type.qualifiedName() + "#" + method.name()));
                }
            }
        }
        return found;
    }
}
