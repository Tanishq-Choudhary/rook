package dev.rook.server.workflow;

import dev.rook.core.job.JobState;
import dev.rook.workflow.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

public class WorkflowGauges implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGauges.class);
    private static final Duration CACHE = Duration.ofSeconds(5);

    private final JobRepository jobs;
    private final JdbcClient jdbc;
    private final WorkerRuntime workers;
    private Map<JobState, Long> byState = Map.of();
    private long outboxPending;
    private double outboxOldestSeconds;
    private long deadLettersOpen;
    private long loadedAtNanos;
    private boolean loaded;

    public WorkflowGauges(JobRepository jobs, JdbcClient jdbc, WorkerRuntime workers) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.workers = workers;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (JobState state : JobState.values()) {
            Gauge.builder("rook.jobs", this, g -> g.jobsIn(state))
                    .description("migration jobs by state")
                    .tag("state", state.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder("rook.outbox.pending", this, WorkflowGauges::pending)
                .description("outbox messages waiting to be published")
                .register(registry);
        Gauge.builder("rook.outbox.oldest.age.seconds", this, WorkflowGauges::oldestAge)
                .description("age of the oldest unpublished outbox message")
                .register(registry);
        Gauge.builder("rook.dead.letters.open", this, WorkflowGauges::deadLetters)
                .description("unresolved dead letters")
                .register(registry);
        if (workers.enabled()) {
            Gauge.builder("rook.workers.alive", workers, WorkerRuntime::alive)
                    .description("worker threads that are running")
                    .register(registry);
        }
    }

    private synchronized double jobsIn(JobState state) {
        refresh();
        return byState.getOrDefault(state, 0L);
    }

    private synchronized double pending() {
        refresh();
        return outboxPending;
    }

    private synchronized double oldestAge() {
        refresh();
        return outboxOldestSeconds;
    }

    private synchronized double deadLetters() {
        refresh();
        return deadLettersOpen;
    }

    private void refresh() {
        long now = System.nanoTime();
        if (loaded && now - loadedAtNanos < CACHE.toNanos()) {
            return;
        }
        try {
            byState = jobs.countByState();
            outboxPending =
                    jdbc.sql("select count(*) from outbox where published_at is null")
                            .query(Long.class)
                            .single();
            Double age =
                    jdbc.sql(
                                    "select extract(epoch from (now() - min(created_at))) from outbox where published_at is null")
                            .query(Double.class)
                            .optional()
                            .orElse(null);
            outboxOldestSeconds = age == null ? 0 : Math.max(0, age);
            deadLettersOpen =
                    jdbc.sql("select count(*) from dead_letter where resolved_at is null")
                            .query(Long.class)
                            .single();
            loaded = true;
            loadedAtNanos = now;
        } catch (RuntimeException e) {
            log.warn("could not refresh workflow gauges: {}", e.getMessage());
            loadedAtNanos = now;
        }
    }
}
