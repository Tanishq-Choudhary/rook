package dev.rook.changes.openapi;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record ApiSchema(
        String ref,
        String type,
        String format,
        boolean nullable,
        boolean deprecated,
        List<String> enumValues,
        Map<String, ApiSchema> properties,
        Set<String> required,
        ApiSchema items,
        ApiSchema additionalProperties) {

    private static final ApiSchema EMPTY =
            new ApiSchema(
                    null, "none", null, false, false, List.of(), Map.of(), Set.of(), null, null);

    public ApiSchema {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        properties =
                properties == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new TreeMap<>(properties));
        required =
                required == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(required));
    }

    public static ApiSchema empty() {
        return EMPTY;
    }

    public static ApiSchema reference(String name) {
        return new ApiSchema(
                name, null, null, false, false, List.of(), Map.of(), Set.of(), null, null);
    }

    public boolean isRef() {
        return ref != null;
    }

    public boolean isEmpty() {
        return !isRef() && "none".equals(type);
    }

    public boolean isObject() {
        return !isRef() && (!properties.isEmpty() || "object".equals(type));
    }

    public boolean isRequired(String property) {
        return required.contains(property);
    }

    public String typeLabel() {
        if (isRef()) {
            return "ref:" + ref;
        }
        String base = type == null ? "any" : type;
        if (format != null && !format.isBlank()) {
            base = base + ":" + format;
        }
        if ("array".equals(type) && items != null) {
            base = base + "[" + items.typeLabel() + "]";
        }
        return base;
    }
}
