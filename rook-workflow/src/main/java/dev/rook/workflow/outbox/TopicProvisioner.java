package dev.rook.workflow.outbox;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;

public final class TopicProvisioner {

    private TopicProvisioner() {}

    public static void ensure(
            Map<String, Object> adminConfig,
            Map<String, Integer> partitionsByTopic,
            short replication) {
        try (Admin admin = Admin.create(adminConfig)) {
            List<NewTopic> topics =
                    partitionsByTopic.entrySet().stream()
                            .map(e -> new NewTopic(e.getKey(), e.getValue(), replication))
                            .toList();
            for (NewTopic topic : topics) {
                try {
                    admin.createTopics(List.of(topic)).all().get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException(
                                "cannot create topic " + topic.name(), e.getCause());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("topic creation timed out", e);
        }
    }
}
