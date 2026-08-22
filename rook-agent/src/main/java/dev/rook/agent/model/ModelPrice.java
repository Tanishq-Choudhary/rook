package dev.rook.agent.model;

public record ModelPrice(
        long inputMicrosPerMillionTokens,
        long cachedInputMicrosPerMillionTokens,
        long outputMicrosPerMillionTokens) {

    public static final ModelPrice FREE = new ModelPrice(0, 0, 0);

    public ModelPrice {
        if (inputMicrosPerMillionTokens < 0
                || cachedInputMicrosPerMillionTokens < 0
                || outputMicrosPerMillionTokens < 0) {
            throw new IllegalArgumentException("prices cannot be negative");
        }
    }

    public long costMicros(Usage usage) {
        long uncached = usage.inputTokens() - usage.cachedInputTokens();
        return ceilDiv(uncached * inputMicrosPerMillionTokens)
                + ceilDiv(usage.cachedInputTokens() * cachedInputMicrosPerMillionTokens)
                + ceilDiv(usage.outputTokens() * outputMicrosPerMillionTokens);
    }

    private static long ceilDiv(long scaled) {
        return (scaled + 999_999L) / 1_000_000L;
    }
}
