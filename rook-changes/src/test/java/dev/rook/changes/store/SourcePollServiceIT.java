package dev.rook.changes.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.changes.source.JdbcSnapshotStore;
import dev.rook.changes.source.PollOutcome;
import dev.rook.changes.source.Snapshot;
import dev.rook.changes.source.SourcePoller;
import dev.rook.changes.source.WatchedSource;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.error.RookException;
import dev.rook.core.hash.Hashes;
import dev.rook.core.id.Ids;
import dev.rook.testkit.TestDatabases;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SourcePollServiceIT {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T09:00:00Z"), ZoneOffset.UTC);

    private JdbcClient jdbc;
    private SourceRepository sources;
    private final List<String> metricLog = new ArrayList<>();
    private final PollMetrics metrics =
            new PollMetrics() {
                @Override
                public void polled(SourceKind kind, String outcome, Duration took) {
                    metricLog.add(kind + ":" + outcome);
                }

                @Override
                public void ingested(ChangeSet set, boolean created) {
                    metricLog.add("set:" + set.toVersion());
                }
            };

    private SourcePollService service(Map<SourceKind, SourcePoller> pollers) {
        return new SourcePollService(
                sources, ingestion, new JdbcSnapshotStore(jdbc), pollers, metrics, CLOCK);
    }

    private ChangeIngestion ingestion;

    private static SourcePoller poller(
            SourceKind kind, Function<WatchedSource, PollOutcome> function) {
        return new SourcePoller() {
            @Override
            public SourceKind kind() {
                return kind;
            }

            @Override
            public PollOutcome poll(WatchedSource source) {
                return function.apply(source);
            }
        };
    }

    @BeforeEach
    void setUp() {
        DataSource ds = TestDatabases.fresh();
        jdbc = JdbcClient.create(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ingestion = new ChangeIngestion(jdbc, tx, CLOCK);
        sources = new SourceRepository(jdbc);
        metricLog.clear();
    }

    private static WatchedSource source(String id, SourceKind kind) {
        return new WatchedSource(
                id, kind, id, "http://example.test/" + id, null, null, null, null, null, 60, true);
    }

    private static PollOutcome change(String sourceId, String from, String to) {
        ChangeEvent event =
                ChangeEvent.of(
                        sourceId,
                        from,
                        to,
                        ChangeKind.OPERATION_REMOVED,
                        SymbolRef.operation("GET", "/x"),
                        Map.of(),
                        "removed");
        ChangeSet set =
                new ChangeSet(
                        Ids.newId(),
                        sourceId,
                        SourceKind.OPENAPI,
                        from,
                        to,
                        List.of(event),
                        List.of(),
                        CLOCK.instant());
        Snapshot snapshot =
                new Snapshot(
                        Ids.newId(),
                        sourceId,
                        to,
                        Hashes.sha256Hex(sourceId + to),
                        "spec".getBytes(StandardCharsets.UTF_8),
                        CLOCK.instant());
        return PollOutcome.changed(List.of(set), List.of(snapshot), "\"e-" + to + "\"", to);
    }

    @Test
    void dueSourcesAreClaimedPolledAndPersisted() {
        sources.register(source("a", SourceKind.OPENAPI));
        SourcePollService service =
                service(
                        Map.of(
                                SourceKind.OPENAPI,
                                poller(SourceKind.OPENAPI, s -> change(s.id(), "1", "2"))));
        List<PollReport> reports = service.pollDue(10);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).failed()).isFalse();
        assertThat(reports.get(0).changeSets()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from change_set").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(sources.find("a").orElseThrow().lastVersion()).isEqualTo("2");
        assertThat(metricLog).contains("set:2", "OPENAPI:changed");
    }

    @Test
    void aSourceIsNotPolledAgainBeforeItsIntervalElapses() {
        sources.register(source("a", SourceKind.OPENAPI));
        SourcePollService service =
                service(
                        Map.of(
                                SourceKind.OPENAPI,
                                poller(SourceKind.OPENAPI, s -> change(s.id(), "1", "2"))));
        assertThat(service.pollDue(10)).hasSize(1);
        assertThat(service.pollDue(10)).isEmpty();
    }

    @Test
    void aFailingSourceDoesNotStopTheOthers() {
        sources.register(source("bad", SourceKind.OPENAPI));
        sources.register(source("good", SourceKind.MAVEN));
        SourcePoller failing =
                poller(
                        SourceKind.OPENAPI,
                        s -> {
                            throw RookException.unavailable("upstream down", null);
                        });
        SourcePollService service =
                service(
                        Map.of(
                                SourceKind.OPENAPI,
                                failing,
                                SourceKind.MAVEN,
                                poller(
                                        SourceKind.MAVEN,
                                        s -> PollOutcome.unchanged(null, "1.0.0"))));
        List<PollReport> reports = service.pollDue(10);
        assertThat(reports).hasSize(2);
        assertThat(reports)
                .filteredOn(PollReport::failed)
                .extracting(PollReport::sourceId)
                .containsExactly("bad");
        assertThat(sources.find("bad").orElseThrow().lastError()).contains("upstream down");
        assertThat(sources.find("good").orElseThrow().lastError()).isNull();
        assertThat(metricLog).contains("OPENAPI:failed", "MAVEN:unchanged");
    }

    @Test
    void sourcesWithoutAPollerAreReportedAsFailures() {
        sources.register(source("feed", SourceKind.RELEASE_FEED));
        List<PollReport> reports = service(Map.of()).pollDue(10);
        assertThat(reports)
                .singleElement()
                .satisfies(r -> assertThat(r.error()).contains("no poller"));
    }

    @Test
    void aPersistenceFailureRollsBackAndIsRecorded() {
        sources.register(source("a", SourceKind.OPENAPI));
        SourcePoller poisoned =
                poller(
                        SourceKind.OPENAPI,
                        s -> {
                            PollOutcome good = change(s.id(), "1", "2");
                            ChangeSet orphan = change("ghost", "1", "2").changeSets().get(0);
                            return PollOutcome.changed(
                                    List.of(good.changeSets().get(0), orphan),
                                    good.snapshots(),
                                    "\"e\"",
                                    "2");
                        });
        List<PollReport> reports = service(Map.of(SourceKind.OPENAPI, poisoned)).pollDue(10);
        assertThat(reports.get(0).failed()).isTrue();
        assertThat(jdbc.sql("select count(*) from change_set").query(Integer.class).single())
                .isZero();
        assertThat(sources.find("a").orElseThrow().lastVersion()).isNull();
        assertThat(sources.find("a").orElseThrow().lastError()).isNotNull();
    }

    @Test
    void pollNowIgnoresTheSchedule() {
        sources.register(source("a", SourceKind.OPENAPI));
        SourcePollService service =
                service(
                        Map.of(
                                SourceKind.OPENAPI,
                                poller(SourceKind.OPENAPI, s -> change(s.id(), "1", "2"))));
        service.pollDue(10);
        assertThat(service.pollNow("a").failed()).isFalse();
    }

    @Test
    void pollNowForAnUnknownSourceIsNotFound() {
        assertThatThrownBy(() -> service(Map.of()).pollNow("nope"))
                .isInstanceOf(RookException.class);
    }
}
