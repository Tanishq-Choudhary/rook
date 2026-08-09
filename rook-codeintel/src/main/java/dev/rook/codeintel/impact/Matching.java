package dev.rook.codeintel.impact;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.TypeInfo;
import dev.rook.codeintel.java.Model.Usage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Matching {

    private Matching() {}

    static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    static String packageOf(String qualified) {
        String[] parts = qualified.split("\\.");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                break;
            }
            if (out.length() > 0) {
                out.append('.');
            }
            out.append(part);
        }
        String pkg = out.toString();
        return pkg.equals(qualified) ? pkg.substring(0, Math.max(0, pkg.lastIndexOf('.'))) : pkg;
    }

    static boolean sees(FileIndex file, String qualified) {
        return file.imports(qualified) || file.packageName().equals(packageOf(qualified));
    }

    static ImpactLocation location(
            Usage usage, Confidence confidence, String target, boolean test) {
        return new ImpactLocation(
                usage.location().file(),
                usage.location().line(),
                usage.location().column(),
                usage.location().endLine(),
                usage.snippet(),
                usage.enclosing(),
                usage.kind().name(),
                confidence,
                target,
                test);
    }

    static ImpactLocation declaration(
            FileIndex file,
            int line,
            String kind,
            Confidence confidence,
            String target,
            String enclosing) {
        return new ImpactLocation(
                file.path(), line, 0, line, "", enclosing, kind, confidence, target, file.test());
    }

    static boolean extendsType(CodeIndex index, TypeInfo type, String ownerSimple, String owner) {
        return extendsType(index, type, ownerSimple, owner, new HashSet<>(), 0);
    }

    private static boolean extendsType(
            CodeIndex index,
            TypeInfo type,
            String ownerSimple,
            String owner,
            Set<String> seen,
            int depth) {
        if (depth > 6 || !seen.add(type.qualifiedName())) {
            return false;
        }
        for (String superType : type.superTypes()) {
            if (superType.equals(owner) || simpleName(superType).equals(ownerSimple)) {
                return true;
            }
            for (TypeInfo parent : index.typesBySimpleName(simpleName(superType))) {
                if (extendsType(index, parent, ownerSimple, owner, seen, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isRepoSubtype(
            CodeIndex index, String scopeType, String ownerSimple, String owner) {
        for (TypeInfo type : index.typesBySimpleName(simpleName(scopeType))) {
            if (extendsType(index, type, ownerSimple, owner)) {
                return true;
            }
        }
        return false;
    }

    static List<String> parametersOf(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < open) {
            return List.of();
        }
        String inner = signature.substring(open + 1, close).strip();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, i).strip());
                start = i + 1;
            }
        }
        parts.add(inner.substring(start).strip());
        return parts;
    }

    static boolean sameParameters(String resolvedSignature, List<String> expected) {
        List<String> actual = parametersOf(resolvedSignature);
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            if (!erasedSimple(actual.get(i)).equals(erasedSimple(expected.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private static String erasedSimple(String type) {
        int generic = type.indexOf('<');
        String raw = generic < 0 ? type : type.substring(0, generic);
        return simpleName(raw.replace("[]", "")).strip();
    }
}
