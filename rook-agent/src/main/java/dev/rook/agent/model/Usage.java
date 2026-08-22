package dev.rook.agent.model;

public record Usage(long inputTokens, long outputTokens, long cachedInputTokens) {

    public static final Usage NONE = new Usage(0, 0, 0);

    public Usage {
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
        if (cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException("cached tokens are part of the input tokens");
        }
    }

    public Usage plus(Usage other) {
        return new Usage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cachedInputTokens + other.cachedInputTokens);
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
