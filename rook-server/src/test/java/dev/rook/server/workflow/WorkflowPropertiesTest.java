package dev.rook.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkflowPropertiesTest {

    private static WorkflowProperties with(Integer workers, Integer partitions) {
        return new WorkflowProperties(
                null, null, workers, null, null, null, null, null, partitions, null, null);
    }

    @Test
    void defaultsAreFilledIn() {
        WorkflowProperties properties = with(null, null);
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(properties.workers()).isEqualTo(4);
        assertThat(properties.lease()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.heartbeatEvery()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.topicPartitions()).isEqualTo(3);
        assertThat(properties.fanoutGroup()).isEqualTo("rook-fanout");
    }

    @Test
    void workersAndPartitionsMustBePositive() {
        assertThatThrownBy(() -> with(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
