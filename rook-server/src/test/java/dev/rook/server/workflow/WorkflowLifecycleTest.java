package dev.rook.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.workflow.fanout.FanoutConfig;
import dev.rook.workflow.fanout.FanoutConsumer;
import dev.rook.workflow.fanout.FanoutMetrics;
import dev.rook.workflow.outbox.OutboxRelay;
import dev.rook.workflow.outbox.RelayConfig;
import dev.rook.workflow.outbox.RelayMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowLifecycleTest {

    private static WorkflowLifecycle lifecycle() {
        WorkflowProperties properties =
                new WorkflowProperties(
                        null, "127.0.0.1:1", null, null, null, null, null, null, null, null, null);
        OutboxRelay relay =
                new OutboxRelay(
                        null,
                        null,
                        null,
                        null,
                        Clock.systemUTC(),
                        RelayConfig.standard(),
                        RelayMetrics.NOOP);
        FanoutConsumer fanout =
                new FanoutConsumer(
                        "127.0.0.1:1", null, FanoutConfig.standard(), FanoutMetrics.NOOP);
        WorkerRuntime workers = new WorkerRuntime(null, List.of(), properties, null);
        return new WorkflowLifecycle(
                "127.0.0.1:1",
                Map.of("rook.change-sets", 1),
                relay,
                fanout,
                workers,
                Duration.ofSeconds(1));
    }

    @Test
    void shutdownIsPromptWhileKafkaIsUnreachable() throws Exception {
        WorkflowLifecycle lifecycle = lifecycle();
        lifecycle.start();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        Thread.sleep(500);
        long started = System.nanoTime();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(12));
    }

    @Test
    void stoppingAnIdleLifecycleDoesNothing() {
        WorkflowLifecycle lifecycle = lifecycle();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void aRuntimeWithoutHandlersHasNoWorkers() {
        WorkerRuntime workers =
                new WorkerRuntime(
                        null,
                        List.of(),
                        new WorkflowProperties(
                                null, null, null, null, null, null, null, null, null, null, null),
                        null);
        assertThat(workers.enabled()).isFalse();
        assertThat(workers.alive()).isZero();
        assertThat(workers.expected()).isZero();
        workers.close();
    }
}
