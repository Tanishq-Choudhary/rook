package dev.rook.server.workflow;

import dev.rook.workflow.outbox.RelayMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;

public class MicrometerRelayMetrics implements RelayMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger leading = new AtomicInteger();

    public MicrometerRelayMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("rook.outbox.relay.leader", leading, AtomicInteger::get)
                .description("1 while this instance holds the outbox relay lock")
                .register(registry);
    }

    @Override
    public void published(String topic) {
        count("rook.outbox.published", "messages sent to Kafka", topic);
    }

    @Override
    public void failed(String topic) {
        count("rook.outbox.failed", "failed send attempts", topic);
    }

    @Override
    public void parked(String topic) {
        count("rook.outbox.parked", "messages parked after repeated failures", topic);
    }

    @Override
    public void leader(boolean isLeading) {
        leading.set(isLeading ? 1 : 0);
    }

    private void count(String name, String description, String topic) {
        Counter.builder(name)
                .description(description)
                .tag("topic", topic)
                .register(registry)
                .increment();
    }
}
