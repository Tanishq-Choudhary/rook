package dev.rook.testkit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

public final class TestKafka {

    private static KafkaContainer container;

    private TestKafka() {}

    public static synchronized KafkaContainer container() {
        if (container == null) {
            container = new KafkaContainer("apache/kafka:4.2.1");
            container.start();
        }
        return container;
    }

    public static String bootstrapServers() {
        return container().getBootstrapServers();
    }

    public static void createTopic(String name, int partitions) {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException(e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> producerConfig() {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.ACKS_CONFIG, "all");
    }

    public static Map<String, Object> consumerConfig(String groupId) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);
    }
}
