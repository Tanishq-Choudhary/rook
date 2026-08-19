package dev.rook.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.testkit.TestKafka;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;

class TopicProvisionerIT {

    private Map<String, Object> adminConfig() {
        return Map.of("bootstrap.servers", TestKafka.bootstrapServers());
    }

    private int partitionsOf(String topic) throws Exception {
        try (Admin admin = Admin.create(adminConfig())) {
            return admin.describeTopics(List.of(topic))
                    .allTopicNames()
                    .get(30, TimeUnit.SECONDS)
                    .get(topic)
                    .partitions()
                    .size();
        }
    }

    @Test
    void createsMissingTopicsWithTheRequestedPartitions() throws Exception {
        String topic = "provision-" + UUID.randomUUID().toString().substring(0, 8);
        TopicProvisioner.ensure(adminConfig(), Map.of(topic, 4), (short) 1);
        assertThat(partitionsOf(topic)).isEqualTo(4);
    }

    @Test
    void existingTopicsAreLeftAlone() throws Exception {
        String topic = "provision-" + UUID.randomUUID().toString().substring(0, 8);
        TopicProvisioner.ensure(adminConfig(), Map.of(topic, 2), (short) 1);
        TopicProvisioner.ensure(adminConfig(), Map.of(topic, 6), (short) 1);
        assertThat(partitionsOf(topic)).isEqualTo(2);
    }

    @Test
    void impossibleReplicationIsReported() {
        String topic = "provision-" + UUID.randomUUID().toString().substring(0, 8);
        assertThatThrownBy(
                        () -> TopicProvisioner.ensure(adminConfig(), Map.of(topic, 1), (short) 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(topic);
    }
}
