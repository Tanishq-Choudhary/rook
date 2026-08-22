package dev.rook.server.changes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.SourceRepository;
import dev.rook.core.change.SourceKind;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        properties = {
            "rook.changes.allow-local-http=true",
            "rook.changes.scheduler-delay=PT0.2S",
            "rook.role=worker",
            "rook.workflow.enabled=false"
        })
@Testcontainers
class SchedulerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final FakeUpstream upstream = new FakeUpstream();

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    @Autowired SourceRepository sources;
    @Autowired JdbcClient jdbc;

    @Test
    void theSchedulerPollsRegisteredSourcesOnItsOwn() {
        sources.register(
                new WatchedSource(
                        "auto",
                        SourceKind.OPENAPI,
                        "auto",
                        upstream.url(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        30,
                        true));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(sources.find("auto").orElseThrow().lastVersion())
                                        .isEqualTo("1"));
        Integer snapshots =
                jdbc.sql("select count(*) from source_snapshot where source_id = 'auto'")
                        .query(Integer.class)
                        .single();
        assertThat(snapshots).isEqualTo(1);
    }
}
