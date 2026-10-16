package dev.rook.agent.model;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode arguments, JsonNode extra) {

    public ToolCall(String id, String name, JsonNode arguments) {
        this(id, name, arguments, null);
    }

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        if (id.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("tool calls need an id and a name");
        }
    }
}
