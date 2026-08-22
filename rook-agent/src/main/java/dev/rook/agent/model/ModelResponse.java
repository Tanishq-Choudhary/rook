package dev.rook.agent.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record ModelResponse(
        String text,
        List<ToolCall> toolCalls,
        Usage usage,
        StopReason stopReason,
        String model,
        Duration latency) {

    public ModelResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.NONE : usage;
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(model, "model");
        latency = latency == null ? Duration.ZERO : latency;
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }

    public ModelMessage asMessage() {
        return ModelMessage.assistant(text, toolCalls);
    }
}
