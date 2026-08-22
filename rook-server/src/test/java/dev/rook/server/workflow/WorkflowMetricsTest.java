package dev.rook.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.job.JobState;
import dev.rook.workflow.fanout.FanoutResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @Test
    void stepsAreCountedAndTimedByStateAndOutcome() {
        MicrometerWorkflowMetrics metrics = new MicrometerWorkflowMetrics(registry);
        metrics.step(JobState.INDEXING, "advanced", Duration.ofMillis(40));
        metrics.step(JobState.INDEXING, "advanced", Duration.ofMillis(60));
        metrics.step(JobState.INDEXING, "retry", Duration.ofMillis(5));
        assertThat(
                        registry.get("rook.job.steps")
                                .tag("state", "indexing")
                                .tag("outcome", "advanced")
                                .counter()
                                .count())
                .isEqualTo(2.0);
        assertThat(registry.get("rook.job.steps").tag("outcome", "retry").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("rook.job.step.duration").tag("state", "indexing").timer().count())
                .isEqualTo(3);
    }

    @Test
    void leaseLossesAndClaimsAreCounted() {
        MicrometerWorkflowMetrics metrics = new MicrometerWorkflowMetrics(registry);
        metrics.leaseLost(JobState.VERIFYING);
        metrics.claimed(true);
        metrics.claimed(false);
        metrics.claimed(false);
        assertThat(registry.get("rook.job.lease.lost").tag("state", "verifying").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("rook.job.claims").tag("reclaimed", "true").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("rook.job.claims").tag("reclaimed", "false").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void relayCountersAreKeyedByTopicAndLeadershipIsAGauge() {
        MicrometerRelayMetrics metrics = new MicrometerRelayMetrics(registry);
        metrics.published("a");
        metrics.published("a");
        metrics.published("b");
        metrics.failed("a");
        metrics.parked("b");
        assertThat(registry.get("rook.outbox.published").tag("topic", "a").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("rook.outbox.published").tag("topic", "b").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("rook.outbox.failed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rook.outbox.parked").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rook.outbox.relay.leader").gauge().value()).isZero();
        metrics.leader(true);
        assertThat(registry.get("rook.outbox.relay.leader").gauge().value()).isEqualTo(1.0);
        metrics.leader(false);
        assertThat(registry.get("rook.outbox.relay.leader").gauge().value()).isZero();
    }

    @Test
    void fanoutCountsOutcomesAndCreatedJobs() {
        MicrometerFanoutMetrics metrics = new MicrometerFanoutMetrics(registry);
        metrics.handled(new FanoutResult(FanoutResult.Outcome.JOBS_CREATED, 3, 0));
        metrics.handled(new FanoutResult(FanoutResult.Outcome.JOBS_CREATED, 2, 1));
        metrics.handled(new FanoutResult(FanoutResult.Outcome.DUPLICATE, 0, 0));
        metrics.failed();
        assertThat(
                        registry.get("rook.fanout.messages")
                                .tag("outcome", "jobs_created")
                                .counter()
                                .count())
                .isEqualTo(2.0);
        assertThat(
                        registry.get("rook.fanout.messages")
                                .tag("outcome", "duplicate")
                                .counter()
                                .count())
                .isEqualTo(1.0);
        assertThat(registry.get("rook.fanout.jobs").counter().count()).isEqualTo(5.0);
        assertThat(registry.get("rook.fanout.failures").counter().count()).isEqualTo(1.0);
    }
}
