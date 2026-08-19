package dev.rook.workflow.fanout;

import java.time.Duration;

public record FanoutConfig(
        String topic, String groupId, int maxAttempts, Duration pollTimeout, Duration retryPause) {

    public FanoutConfig {
        if (topic == null || topic.isBlank() || groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("topic and group id are required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
    }

    public static FanoutConfig standard() {
        return new FanoutConfig(
                "rook.change-sets",
                "rook-fanout",
                5,
                Duration.ofMillis(500),
                Duration.ofSeconds(2));
    }

    public FanoutConfig withTopic(String newTopic) {
        return new FanoutConfig(newTopic, groupId, maxAttempts, pollTimeout, retryPause);
    }

    public FanoutConfig withGroupId(String newGroupId) {
        return new FanoutConfig(topic, newGroupId, maxAttempts, pollTimeout, retryPause);
    }

    public FanoutConfig withMaxAttempts(int attempts) {
        return new FanoutConfig(topic, groupId, attempts, pollTimeout, retryPause);
    }

    public FanoutConfig withRetryPause(Duration pause) {
        return new FanoutConfig(topic, groupId, maxAttempts, pollTimeout, pause);
    }
}
