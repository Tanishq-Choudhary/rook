package dev.rook.server.workflow;

import dev.rook.core.job.JobState;
import dev.rook.workflow.WorkflowMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

public class MicrometerWorkflowMetrics implements WorkflowMetrics {

    private final MeterRegistry registry;

    public MicrometerWorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void step(JobState state, String outcome, Duration took) {
        String stateTag = state.name().toLowerCase(Locale.ROOT);
        Counter.builder("rook.job.steps")
                .description("job step executions by state and outcome")
                .tag("state", stateTag)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("rook.job.step.duration")
                .description("time spent executing one job step")
                .tag("state", stateTag)
                .publishPercentileHistogram()
                .register(registry)
                .record(took);
    }

    @Override
    public void leaseLost(JobState state) {
        Counter.builder("rook.job.lease.lost")
                .description("steps abandoned because the lease was lost")
                .tag("state", state.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
    }

    @Override
    public void claimed(boolean reclaimed) {
        Counter.builder("rook.job.claims")
                .description("jobs claimed by workers")
                .tag("reclaimed", Boolean.toString(reclaimed))
                .register(registry)
                .increment();
    }
}
