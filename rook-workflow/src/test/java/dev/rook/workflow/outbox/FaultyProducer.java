package dev.rook.workflow.outbox;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;

final class FaultyProducer {

    private final List<ProducerRecord<String, String>> sent = new CopyOnWriteArrayList<>();
    private volatile Predicate<ProducerRecord<String, String>> failing = record -> false;
    private final Producer<String, String> producer = proxy();

    @SuppressWarnings("unchecked")
    private Producer<String, String> proxy() {
        return (Producer<String, String>)
                Proxy.newProxyInstance(
                        Producer.class.getClassLoader(),
                        new Class<?>[] {Producer.class},
                        (instance, method, args) -> {
                            if (!method.getName().equals("send")) {
                                return null;
                            }
                            @SuppressWarnings("unchecked")
                            ProducerRecord<String, String> record =
                                    (ProducerRecord<String, String>) args[0];
                            if (failing.test(record)) {
                                return CompletableFuture.failedFuture(
                                        new TimeoutException("broker unavailable"));
                            }
                            sent.add(record);
                            return CompletableFuture.completedFuture(
                                    new RecordMetadata(
                                            new TopicPartition(record.topic(), 0), 0, 0, 0, 0, 0));
                        });
    }

    Producer<String, String> producer() {
        return producer;
    }

    void failWhen(Predicate<ProducerRecord<String, String>> predicate) {
        this.failing = predicate;
    }

    void healthy() {
        this.failing = record -> false;
    }

    List<ProducerRecord<String, String>> sent() {
        return sent;
    }
}
