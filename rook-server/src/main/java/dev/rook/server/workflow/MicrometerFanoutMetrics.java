package dev.rook.server.workflow;

import dev.rook.workflow.fanout.FanoutMetrics;
import dev.rook.workflow.fanout.FanoutResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

public class MicrometerFanoutMetrics implements FanoutMetrics {

    private final MeterRegistry registry;

    public MicrometerFanoutMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void handled(FanoutResult result) {
        Counter.builder("rook.fanout.messages")
                .description("change set messages handled by outcome")
                .tag("outcome", result.outcome().name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
        if (result.created() > 0) {
            Counter.builder("rook.fanout.jobs")
                    .description("migration jobs created from change sets")
                    .register(registry)
                    .increment(result.created());
        }
    }

    @Override
    public void failed() {
        Counter.builder("rook.fanout.failures")
                .description("change set messages that failed and will be retried")
                .register(registry)
                .increment();
    }
}
