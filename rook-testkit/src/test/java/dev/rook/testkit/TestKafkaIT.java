package dev.rook.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

class TestKafkaIT {

    @Test
    void messagesRoundTripThroughTheSharedBroker() throws Exception {
        TestKafka.createTopic("testkit-roundtrip", 1);
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(TestKafka.producerConfig())) {
            producer.send(new ProducerRecord<>("testkit-roundtrip", "k", "hello")).get();
        }
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(TestKafka.consumerConfig("testkit-group"))) {
            consumer.subscribe(List.of("testkit-roundtrip"));
            String value = null;
            for (int i = 0; i < 20 && value == null; i++) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofMillis(500))) {
                    value = record.value();
                }
            }
            assertThat(value).isEqualTo("hello");
        }
    }

    @Test
    void creatingATopicTwiceIsHarmless() {
        TestKafka.createTopic("testkit-twice", 2);
        TestKafka.createTopic("testkit-twice", 2);
    }
}
