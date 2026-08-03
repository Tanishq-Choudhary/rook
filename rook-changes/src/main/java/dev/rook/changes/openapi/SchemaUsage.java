package dev.rook.changes.openapi;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class SchemaUsage {

    enum Direction {
        REQUEST,
        RESPONSE
    }

    private final Map<String, Set<Direction>> byName = new HashMap<>();

    static SchemaUsage of(OpenApiDocument... documents) {
        SchemaUsage usage = new SchemaUsage();
        for (OpenApiDocument document : documents) {
            usage.collect(document);
        }
        return usage;
    }

    Set<Direction> directions(String schemaName) {
        Set<Direction> found = byName.get(schemaName);
        if (found == null || found.isEmpty()) {
            return EnumSet.allOf(Direction.class);
        }
        return EnumSet.copyOf(found);
    }

    private void collect(OpenApiDocument document) {
        for (ApiOperation operation : document.operations().values()) {
            visit(document, operation.requestBody(), Direction.REQUEST, new HashSet<>());
            for (ApiParameter parameter : operation.parameters()) {
                visit(document, parameter.schema(), Direction.REQUEST, new HashSet<>());
            }
            for (ApiSchema response : operation.responses().values()) {
                visit(document, response, Direction.RESPONSE, new HashSet<>());
            }
        }
    }

    private void visit(
            OpenApiDocument document, ApiSchema schema, Direction direction, Set<String> seen) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        if (schema.isRef()) {
            if (!seen.add(schema.ref())) {
                return;
            }
            byName.computeIfAbsent(schema.ref(), k -> EnumSet.noneOf(Direction.class))
                    .add(direction);
            ApiSchema target = document.schema(schema.ref());
            if (target != null) {
                visit(document, target, direction, seen);
            }
            return;
        }
        for (ApiSchema property : schema.properties().values()) {
            visit(document, property, direction, seen);
        }
        visit(document, schema.items(), direction, seen);
        visit(document, schema.additionalProperties(), direction, seen);
    }
}
