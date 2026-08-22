package dev.rook.server.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rook.workflow")
public record WorkflowProperties(
        Boolean enabled,
        String bootstrapServers,
        Integer workers,
        Duration lease,
        Duration heartbeatEvery,
        Duration stepTimeout,
        Duration idleSleep,
        Duration shutdownGrace,
        Integer topicPartitions,
        Duration relayPollInterval,
        String fanoutGroup) {

    public WorkflowProperties {
        enabled = enabled == null || enabled;
        bootstrapServers = bootstrapServers == null ? "localhost:9092" : bootstrapServers;
        workers = workers == null ? 4 : workers;
        lease = lease == null ? Duration.ofSeconds(60) : lease;
        heartbeatEvery = heartbeatEvery == null ? Duration.ofSeconds(15) : heartbeatEvery;
        stepTimeout = stepTimeout == null ? Duration.ofMinutes(15) : stepTimeout;
        idleSleep = idleSleep == null ? Duration.ofMillis(250) : idleSleep;
        shutdownGrace = shutdownGrace == null ? Duration.ofSeconds(20) : shutdownGrace;
        topicPartitions = topicPartitions == null ? 3 : topicPartitions;
        relayPollInterval = relayPollInterval == null ? Duration.ofMillis(250) : relayPollInterval;
        fanoutGroup = fanoutGroup == null ? "rook-fanout" : fanoutGroup;
        if (workers < 1 || topicPartitions < 1) {
            throw new IllegalArgumentException("workers and topic partitions must be positive");
        }
    }
}
