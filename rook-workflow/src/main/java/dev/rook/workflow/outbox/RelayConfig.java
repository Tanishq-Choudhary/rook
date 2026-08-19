package dev.rook.workflow.outbox;

import dev.rook.workflow.Backoff;
import java.time.Duration;

public record RelayConfig(
        int batchSize,
        int maxAttempts,
        Duration sendTimeout,
        Duration pollInterval,
        Backoff backoff,
        long leaderLockKey) {

    public RelayConfig {
        if (batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("invalid relay configuration");
        }
    }

    public static RelayConfig standard() {
        return new RelayConfig(
                100,
                12,
                Duration.ofSeconds(10),
                Duration.ofMillis(250),
                new Backoff(Duration.ofSeconds(2), 2.0, Duration.ofMinutes(5)),
                7_330_123L);
    }

    public RelayConfig withMaxAttempts(int attempts) {
        return new RelayConfig(
                batchSize, attempts, sendTimeout, pollInterval, backoff, leaderLockKey);
    }

    public RelayConfig withPollInterval(Duration interval) {
        return new RelayConfig(
                batchSize, maxAttempts, sendTimeout, interval, backoff, leaderLockKey);
    }

    public RelayConfig withBackoff(Backoff newBackoff) {
        return new RelayConfig(
                batchSize, maxAttempts, sendTimeout, pollInterval, newBackoff, leaderLockKey);
    }
}
