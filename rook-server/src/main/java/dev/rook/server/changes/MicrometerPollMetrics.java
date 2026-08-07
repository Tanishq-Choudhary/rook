package dev.rook.server.changes;

import dev.rook.changes.store.PollMetrics;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

public class MicrometerPollMetrics implements PollMetrics {

    private final MeterRegistry registry;

    public MicrometerPollMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void polled(SourceKind kind, String outcome, Duration took) {
        String kindTag = kind.name().toLowerCase(Locale.ROOT);
        Counter.builder("rook.source.polls")
                .description("source polls by kind and outcome")
                .tag("kind", kindTag)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
        Timer.builder("rook.source.poll.duration")
                .description("time spent polling one source")
                .tag("kind", kindTag)
                .publishPercentileHistogram()
                .register(registry)
                .record(took);
    }

    @Override
    public void ingested(ChangeSet set, boolean created) {
        Counter.builder("rook.change.sets")
                .description("change sets recorded")
                .tag("kind", set.sourceKind().name().toLowerCase(Locale.ROOT))
                .tag("severity", set.highestSeverity().name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
        set.events()
                .forEach(
                        event ->
                                Counter.builder("rook.change.events")
                                        .description("change events recorded")
                                        .tag("kind", event.kind().name().toLowerCase(Locale.ROOT))
                                        .tag(
                                                "severity",
                                                event.severity().name().toLowerCase(Locale.ROOT))
                                        .register(registry)
                                        .increment());
    }
}
