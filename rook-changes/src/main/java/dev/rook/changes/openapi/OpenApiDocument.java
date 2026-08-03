package dev.rook.changes.openapi;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record OpenApiDocument(
        String title,
        String version,
        Map<String, ApiOperation> operations,
        Map<String, ApiSchema> schemas) {

    public OpenApiDocument {
        operations =
                operations == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new TreeMap<>(operations));
        schemas = schemas == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(schemas));
    }

    public ApiSchema schema(String name) {
        return schemas.get(name);
    }
}
