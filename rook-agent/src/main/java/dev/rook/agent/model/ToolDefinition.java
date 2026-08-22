package dev.rook.agent.model;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

public record ToolDefinition(String name, String description, JsonNode inputSchema) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (!name.matches("[a-zA-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("invalid tool name: " + name);
        }
    }
}
