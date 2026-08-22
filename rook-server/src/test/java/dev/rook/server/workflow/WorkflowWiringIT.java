package dev.rook.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.changes.store.ChangeIngestion;
import dev.rook.changes.store.SourceRepository;
import dev.rook.core.job.JobState;
import dev.rook.testkit.TestKafka;
import dev.rook.workflow.JobContext;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.StepResult;
import dev.rook.workflow.repo.RepositoryStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AutoConfigureMetrics
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rook.changes.scheduler-enabled=false",
            "rook.workflow.fanout-group=wiring-it",
            "rook.workflow.relay-poll-interval=PT0.05S",
            "rook.workflow.idle-sleep=PT0.05S",
            "rook.workflow.workers=3"
        })
@Testcontainers
class WorkflowWiringIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final AtomicInteger published = new AtomicInteger();

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("rook.workflow.bootstrap-servers", TestKafka::bootstrapServers);
    }

    @TestConfiguration
    static class Handlers {

        private static StepHandler handler(JobState state, JobState next) {
            return new StepHandler() {
                @Override
                public JobState state() {
                    return state;
                }

                @Override
                public StepResult execute(JobContext context) {
                    if (next == JobState.COMPLETED) {
                        published.incrementAndGet();
                    }
                    return StepResult.advance(next);
                }
            };
        }

        @Bean
        StepHandler indexing() {
            return handler(JobState.INDEXING, JobState.PROPOSING);
        }

        @Bean
        StepHandler proposing() {
            return handler(JobState.PROPOSING, JobState.VERIFYING);
        }

        @Bean
        StepHandler verifying() {
            return handler(JobState.VERIFYING, JobState.PUBLISHING);
        }

        @Bean
        StepHandler publishing() {
            return handler(JobState.PUBLISHING, JobState.COMPLETED);
        }
    }

    @LocalServerPort int port;

    @Autowired SourceRepository sources;
    @Autowired RepositoryStore store;
    @Autowired ChangeIngestion ingestion;
    @Autowired JdbcClient jdbc;
    @Autowired WorkflowHealthIndicator health;
    @Autowired WorkerRuntime workers;

    private String scrape() {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);
    }

    @Test
    void aChangeSetIngestedByTheServerIsMigratedByItsOwnWorkers() {
        WorkflowScenario.watchedRepository(sources, store, "wired-flow", 7001);
        WorkflowScenario.breakingChange(ingestion, "wired-flow");
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.sql(
                                                                "select count(*) from migration_job where state = 'COMPLETED'")
                                                        .query(Integer.class)
                                                        .single())
                                        .isEqualTo(1));
        assertThat(published.get()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from dead_letter").query(Integer.class).single())
                .isZero();
    }

    @Test
    void theWorkflowShowsUpInMetricsAndHealth() {
        WorkflowScenario.watchedRepository(sources, store, "wired-metrics", 7002);
        WorkflowScenario.breakingChange(ingestion, "wired-metrics");
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () -> {
                            String body = scrape();
                            assertThat(body)
                                    .contains("rook_job_steps_total")
                                    .contains("rook_outbox_published_total")
                                    .contains("rook_fanout_jobs_total")
                                    .contains("rook_job_claims_total")
                                    .contains("rook_outbox_relay_leader")
                                    .contains("rook_workers_alive")
                                    .contains("rook_outbox_oldest_age_seconds");
                        });
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(scrape())
                                        .containsPattern(
                                                "rook_jobs\\{[^}]*state=\"completed\"[^}]*} [1-9]"));
        Health result = health.health();
        assertThat(result.getStatus()).isEqualTo(Status.UP);
        assertThat(result.getDetails()).containsEntry("workersExpected", 3);
        assertThat(workers.enabled()).isTrue();
        assertThat(workers.alive()).isEqualTo(3);
    }

    @Test
    void theOverallHealthEndpointStaysUp() {
        String body =
                RestClient.create("http://localhost:" + port)
                        .get()
                        .uri("/actuator/health")
                        .retrieve()
                        .body(String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }
}
