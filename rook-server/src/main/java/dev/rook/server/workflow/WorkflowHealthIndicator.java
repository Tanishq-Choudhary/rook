package dev.rook.server.workflow;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;

public class WorkflowHealthIndicator implements HealthIndicator {

    private final WorkerRuntime workers;
    private final JdbcClient jdbc;

    public WorkflowHealthIndicator(WorkerRuntime workers, JdbcClient jdbc) {
        this.workers = workers;
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        long pending =
                jdbc.sql("select count(*) from outbox where published_at is null")
                        .query(Long.class)
                        .single();
        Health.Builder builder =
                workers.enabled() && workers.alive() == 0 ? Health.down() : Health.up();
        return builder.withDetail("workersExpected", workers.expected())
                .withDetail("workersAlive", workers.alive())
                .withDetail("outboxPending", pending)
                .build();
    }
}
