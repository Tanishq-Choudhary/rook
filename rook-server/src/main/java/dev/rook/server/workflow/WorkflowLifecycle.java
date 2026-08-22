package dev.rook.server.workflow;

import dev.rook.workflow.fanout.FanoutConsumer;
import dev.rook.workflow.outbox.OutboxRelay;
import dev.rook.workflow.outbox.TopicProvisioner;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class WorkflowLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLifecycle.class);

    private final String bootstrapServers;
    private final Map<String, Integer> topics;
    private final OutboxRelay relay;
    private final FanoutConsumer fanout;
    private final WorkerRuntime workers;
    private final Duration shutdownGrace;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean running;
    private Thread bootstrap;

    public WorkflowLifecycle(
            String bootstrapServers,
            Map<String, Integer> topics,
            OutboxRelay relay,
            FanoutConsumer fanout,
            WorkerRuntime workers,
            Duration shutdownGrace) {
        this.bootstrapServers = bootstrapServers;
        this.topics = topics;
        this.relay = relay;
        this.fanout = fanout;
        this.workers = workers;
        this.shutdownGrace = shutdownGrace;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workers.start();
        bootstrap = Thread.ofPlatform().daemon().name("workflow-bootstrap").start(this::connect);
    }

    private void connect() {
        while (stopped.getCount() > 0) {
            try {
                TopicProvisioner.ensure(
                        Map.of(
                                "bootstrap.servers",
                                bootstrapServers,
                                "request.timeout.ms",
                                5000,
                                "default.api.timeout.ms",
                                5000),
                        topics,
                        (short) 1);
                relay.start();
                fanout.start();
                return;
            } catch (RuntimeException e) {
                log.warn("kafka not ready, retrying: {}", e.getMessage());
                if (await(Duration.ofSeconds(3))) {
                    return;
                }
            }
        }
    }

    private boolean await(Duration duration) {
        try {
            return stopped.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        stopped.countDown();
        if (bootstrap != null) {
            try {
                bootstrap.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        fanout.stop();
        relay.stop();
        workers.stop(shutdownGrace);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
