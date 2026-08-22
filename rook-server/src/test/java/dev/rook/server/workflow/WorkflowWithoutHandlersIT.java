package dev.rook.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.SourceRepository;
import dev.rook.testkit.TestKafka;
import dev.rook.workflow.repo.RepositoryStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        properties = {
            "rook.changes.scheduler-enabled=false",
            "rook.workflow.fanout-group=no-handlers-it",
            "rook.workflow.relay-poll-interval=PT0.05S"
        })
@Testcontainers
class WorkflowWithoutHandlersIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("rook.workflow.bootstrap-servers", TestKafka::bootstrapServers);
    }

    @Autowired SourceRepository sources;
    @Autowired RepositoryStore store;
    @Autowired ChangeIngestion ingestion;
    @Autowired JdbcClient jdbc;
    @Autowired WorkerRuntime workers;
    @Autowired WorkflowHealthIndicator health;

    @Test
    void jobsAreCreatedAndWaitWhenNoStepHandlerIsRegistered() throws Exception {
        WorkflowScenario.watchedRepository(sources, store, "idle-flow", 7101);
        WorkflowScenario.breakingChange(ingestion, "idle-flow");
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.sql("select count(*) from migration_job")
                                                        .query(Integer.class)
                                                        .single())
                                        .isEqualTo(1));
        Thread.sleep(1000);
        assertThat(jdbc.sql("select state from migration_job").query(String.class).single())
                .isEqualTo("PENDING");
        assertThat(jdbc.sql("select attempt from migration_job").query(Integer.class).single())
                .isZero();
        assertThat(workers.enabled()).isFalse();
        Health result = health.health();
        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails()).containsEntry("workersAlive", 0);
    }
}
