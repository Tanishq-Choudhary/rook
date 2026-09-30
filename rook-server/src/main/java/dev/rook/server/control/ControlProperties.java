package dev.rook.server.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rook.control")
public record ControlProperties(
        String bootstrapKey,
        Long monthlyBudgetMicros,
        Long jobBudgetMicros,
        Integer rateBurst,
        Double ratePerSecond,
        Integer failureBurst,
        Long maxRequestBytes,
        Long maxWebhookBytes,
        String protectedPrefix,
        String webhookPrefix) {

    public ControlProperties {
        bootstrapKey = bootstrapKey == null || bootstrapKey.isBlank() ? null : bootstrapKey;
        monthlyBudgetMicros = monthlyBudgetMicros == null ? 50_000_000L : monthlyBudgetMicros;
        jobBudgetMicros = jobBudgetMicros == null ? 2_000_000L : jobBudgetMicros;
        rateBurst = rateBurst == null ? 60 : rateBurst;
        ratePerSecond = ratePerSecond == null ? 10.0 : ratePerSecond;
        failureBurst = failureBurst == null ? 20 : failureBurst;
        maxRequestBytes = maxRequestBytes == null ? 1_048_576L : maxRequestBytes;
        maxWebhookBytes = maxWebhookBytes == null ? 26_214_400L : maxWebhookBytes;
        protectedPrefix = protectedPrefix == null ? "/api/" : protectedPrefix;
        webhookPrefix = webhookPrefix == null ? "/webhooks/" : webhookPrefix;
        if (monthlyBudgetMicros < 0 || jobBudgetMicros < 0) {
            throw new IllegalArgumentException("budgets must not be negative");
        }
        if (rateBurst < 1
                || ratePerSecond <= 0
                || failureBurst < 1
                || maxRequestBytes < 1
                || maxWebhookBytes < 1) {
            throw new IllegalArgumentException("rate and size limits must be positive");
        }
    }
}
