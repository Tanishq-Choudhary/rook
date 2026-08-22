package dev.rook.agent.model;

import java.util.List;
import java.util.Objects;

public record ModelRequest(
        String system,
        List<ModelMessage> messages,
        List<ToolDefinition> tools,
        int maxTokens,
        double temperature) {

    public ModelRequest {
        system = system == null ? "" : system;
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a request needs at least one message");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (temperature < 0 || temperature > 1) {
            throw new IllegalArgumentException("temperature must be between 0 and 1");
        }
    }

    public static ModelRequest of(String system, List<ModelMessage> messages, int maxTokens) {
        return new ModelRequest(system, messages, List.of(), maxTokens, 0);
    }

    public ModelRequest withTools(List<ToolDefinition> newTools) {
        return new ModelRequest(system, messages, newTools, maxTokens, temperature);
    }
}
