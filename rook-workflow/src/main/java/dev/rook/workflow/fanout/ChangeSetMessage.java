package dev.rook.workflow.fanout;

import dev.rook.core.change.Severity;
import dev.rook.core.json.Json;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

record ChangeSetMessage(
        UUID changeSetId, String sourceId, boolean requiresMigration, Severity severity) {

    static ChangeSetMessage parse(String payload) {
        JsonNode node = Json.tree(payload);
        if (!node.isObject()) {
            throw new IllegalArgumentException("payload is not an object");
        }
        return new ChangeSetMessage(
                UUID.fromString(text(node, "changeSetId")),
                text(node, "sourceId"),
                flag(node, "requiresMigration"),
                Severity.valueOf(text(node, "highestSeverity")));
    }

    int priority() {
        return switch (severity) {
            case BREAKING -> 20;
            case DEPRECATION -> 10;
            case ADDITIVE, ADVISORY -> 0;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("missing field " + field);
        }
        return value.asString();
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("missing field " + field);
        }
        return value.asBoolean();
    }
}
