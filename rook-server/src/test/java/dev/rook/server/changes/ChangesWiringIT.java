package dev.rook.server.changes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.changes.source.WatchedSource;
import dev.rook.changes.store.PollReport;
import dev.rook.changes.store.SourcePollService;
import dev.rook.changes.store.SourceRepository;
import dev.rook.core.change.SourceKind;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AutoConfigureMetrics
@SpringBootTest(
        properties = {
            "rook.changes.allow-local-http=true",
            "rook.changes.scheduler-enabled=false",
            "rook.workflow.enabled=false"
        })
@Testcontainers
class ChangesWiringIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final FakeUpstream upstream = new FakeUpstream();

    @AfterAll
    static void stopUpstream() {
        upstream.close();
    }

    @Autowired SourceRepository sources;
    @Autowired SourcePollService pollService;
    @Autowired JdbcClient jdbc;
    @Autowired MeterRegistry meters;
    @Autowired ObjectProvider<SourcePollScheduler> scheduler;

    private static WatchedSource source(String id, String location) {
        return new WatchedSource(
                id, SourceKind.OPENAPI, id, location, null, null, null, null, null, 30, true);
    }

    @Test
    void aPolledSourceYieldsAChangeSetAnOutboxMessageAndMetrics() {
        sources.register(source("wired", upstream.url()));
        upstream.serve(FakeUpstream.V1);
        assertThat(pollService.pollNow("wired").failed()).isFalse();
        upstream.serve(FakeUpstream.V2);
        PollReport changed = pollService.pollNow("wired");
        assertThat(changed.changeSets()).isEqualTo(1);
        Integer sets =
                jdbc.sql("select count(*) from change_set where source_id = 'wired'")
                        .query(Integer.class)
                        .single();
        assertThat(sets).isEqualTo(1);
        Integer outbox =
                jdbc.sql("select count(*) from outbox where message_key = 'wired'")
                        .query(Integer.class)
                        .single();
        assertThat(outbox).isEqualTo(1);
        assertThat(meters.get("rook.source.polls").tag("outcome", "changed").counter().count())
                .isGreaterThanOrEqualTo(1);
        assertThat(
                        meters.get("rook.change.events")
                                .tag("kind", "operation_removed")
                                .counter()
                                .count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("rook.source.poll.duration").timer().count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void anUnreachableSourceIsRecordedAsAFailure() {
        sources.register(source("broken", "http://127.0.0.1:1/spec"));
        PollReport report = pollService.pollNow("broken");
        assertThat(report.failed()).isTrue();
        assertThat(sources.find("broken").orElseThrow().lastError()).isNotBlank();
        assertThat(meters.get("rook.source.polls").tag("outcome", "failed").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void theSchedulerIsNotCreatedWhenDisabled() {
        assertThat(scheduler.getIfAvailable()).isNull();
    }
}
