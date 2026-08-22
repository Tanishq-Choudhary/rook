package dev.rook.agent.model;

import java.util.List;
import java.util.Objects;

public record ModelMessage(Role role, String text, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role {
        USER,
        ASSISTANT,
        TOOL_RESULT
    }

    public ModelMessage {
        Objects.requireNonNull(role, "role");
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (role == Role.TOOL_RESULT && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool results need the id of the call they answer");
        }
        if (role != Role.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("only assistant messages carry tool calls");
        }
    }

    public static ModelMessage user(String text) {
        return new ModelMessage(Role.USER, text, List.of(), null);
    }

    public static ModelMessage assistant(String text, List<ToolCall> calls) {
        return new ModelMessage(Role.ASSISTANT, text, calls, null);
    }

    public static ModelMessage toolResult(String toolCallId, String text) {
        return new ModelMessage(Role.TOOL_RESULT, text, List.of(), toolCallId);
    }
}
