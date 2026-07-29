package dev.rook.core.change;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SymbolRef(SymbolKind kind, String name) {

    public SymbolRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("symbol name must not be blank");
        }
    }

    public static SymbolRef operation(String httpMethod, String path) {
        return new SymbolRef(SymbolKind.OPERATION, httpMethod.toUpperCase() + " " + path);
    }

    public static SymbolRef parameter(String operation, String location, String parameter) {
        return new SymbolRef(SymbolKind.PARAMETER, operation + " " + location + ":" + parameter);
    }

    public static SymbolRef schema(String schema) {
        return new SymbolRef(SymbolKind.SCHEMA, schema);
    }

    public static SymbolRef schemaField(String schema, String field) {
        return new SymbolRef(SymbolKind.SCHEMA_FIELD, schema + "." + field);
    }

    public static SymbolRef enumValue(String schemaField, String value) {
        return new SymbolRef(SymbolKind.ENUM_VALUE, schemaField + "=" + value);
    }

    public static SymbolRef javaClass(String qualifiedName) {
        return new SymbolRef(SymbolKind.JAVA_CLASS, qualifiedName);
    }

    public static SymbolRef javaMethod(String owner, String method, List<String> parameterTypes) {
        return new SymbolRef(
                SymbolKind.JAVA_METHOD,
                owner + "#" + method + "(" + String.join(",", parameterTypes) + ")");
    }

    public static SymbolRef javaField(String owner, String field) {
        return new SymbolRef(SymbolKind.JAVA_FIELD, owner + "#" + field);
    }

    public static SymbolRef advisory(String title) {
        return new SymbolRef(SymbolKind.ADVISORY, title);
    }

    public Optional<MethodSignature> asMethod() {
        if (kind != SymbolKind.JAVA_METHOD) {
            return Optional.empty();
        }
        int hash = name.indexOf('#');
        int open = name.indexOf('(', hash);
        if (hash < 0 || open < 0 || !name.endsWith(")")) {
            return Optional.empty();
        }
        String owner = name.substring(0, hash);
        String method = name.substring(hash + 1, open);
        String inner = name.substring(open + 1, name.length() - 1);
        List<String> parameters = new ArrayList<>();
        if (!inner.isEmpty()) {
            int depth = 0;
            int start = 0;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parameters.add(inner.substring(start, i));
                    start = i + 1;
                }
            }
            parameters.add(inner.substring(start));
        }
        return Optional.of(new MethodSignature(owner, method, List.copyOf(parameters)));
    }

    public Optional<String> ownerType() {
        return switch (kind) {
            case JAVA_METHOD -> asMethod().map(MethodSignature::owner);
            case JAVA_FIELD -> Optional.of(name.substring(0, name.indexOf('#')));
            case JAVA_CLASS -> Optional.of(name);
            default -> Optional.empty();
        };
    }

    public record MethodSignature(String owner, String name, List<String> parameterTypes) {}
}
