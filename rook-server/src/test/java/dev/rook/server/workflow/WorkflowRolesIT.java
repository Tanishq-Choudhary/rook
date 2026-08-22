package dev.rook.server.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.workflow.JobRepository;
import dev.rook.workflow.fanout.FanoutConsumer;
import dev.rook.workflow.outbox.OutboxRelay;
import dev.rook.workflow.repo.RepositoryStore;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class WorkflowRolesIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Nested
    @SpringBootTest(properties = {"rook.role=api", "rook.changes.scheduler-enabled=false"})
    class ApiRole {

        @Autowired ApplicationContext context;

        @Test
        void anApiInstanceHoldsStoresButRunsNoBackgroundWork() {
            assertThat(context.getBeanNamesForType(JobRepository.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(RepositoryStore.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
            assertThat(context.getBeanNamesForType(FanoutConsumer.class)).isEmpty();
            assertThat(context.getBeanNamesForType(WorkerRuntime.class)).isEmpty();
            assertThat(context.getBeanNamesForType(WorkflowLifecycle.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(
            properties = {"rook.workflow.enabled=false", "rook.changes.scheduler-enabled=false"})
    class Disabled {

        @Autowired ApplicationContext context;

        @Test
        void aDisabledWorkflowRegistersNothing() {
            assertThat(context.getBeanNamesForType(JobRepository.class)).isEmpty();
            assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
            assertThat(context.getBeanNamesForType(WorkflowLifecycle.class)).isEmpty();
        }
    }
}
