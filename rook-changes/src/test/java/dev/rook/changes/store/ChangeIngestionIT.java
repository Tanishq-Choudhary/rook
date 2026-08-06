package dev.rook.changes.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.changes.source.JdbcSnapshotStore;
import dev.rook.changes.source.PollOutcome;
import dev.rook.changes.source.Snapshot;
import dev.rook.changes.source.WatchedSource;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.MigrationNote;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.core.hash.Hashes;
import dev.rook.core.id.Ids;
import dev.rook.testkit.TestDatabases;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ChangeIngestionIT {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);

    private JdbcClient jdbc;
    private ChangeIngestion ingestion;
    private SourceRepository sources;
    private JdbcSnapshotStore snapshots;

    @BeforeEach
    void setUp() {
        DataSource ds = TestDatabases.fresh();
        jdbc = JdbcClient.create(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ingestion = new ChangeIngestion(jdbc, tx, CLOCK);
        sources = new SourceRepository(jdbc);
        snapshots = new JdbcSnapshotStore(jdbc);
        sources.register(source("acme-api"));
    }

    static WatchedSource source(String id) {
        return new WatchedSource(
                id,
                SourceKind.OPENAPI,
                id,
                "http://example.test/" + id,
                null,
                null,
                null,
                null,
                null,
                900,
                true);
    }

    private static ChangeSet set(String sourceId, String from, String to, int events) {
        List<ChangeEvent> list = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            list.add(
                    ChangeEvent.of(
                            sourceId,
                            from,
                            to,
                            ChangeKind.OPERATION_REMOVED,
                            SymbolRef.operation("GET", "/v1/item" + i),
                            Map.of("operationId", "item" + i),
                            "removed " + i));
        }
        return new ChangeSet(
                Ids.newId(),
                sourceId,
                SourceKind.OPENAPI,
                from,
                to,
                list,
                List.of(new MigrationNote("guide", "body", "https://example.test/guide")),
                Instant.parse("2026-08-10T11:59:00Z"));
    }

    private int count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Integer.class).single();
    }

    @Test
    void ingestingStoresTheSetItsEventsAndOneOutboxMessage() {
        ChangeSet set = set("acme-api", "1", "2", 3);
        ChangeIngestion.IngestResult result = ingestion.ingest(set);
        assertThat(result.status()).isEqualTo(ChangeIngestion.Status.CREATED);
        assertThat(result.changeSetId()).isEqualTo(set.id());
        assertThat(count("change_set")).isEqualTo(1);
        assertThat(count("change_event")).isEqualTo(3);
        assertThat(count("outbox")).isEqualTo(1);
        String payload = jdbc.sql("select payload::text from outbox").query(String.class).single();
        assertThat(payload)
                .contains("\"requiresMigration\": true")
                .contains("\"toVersion\": \"2\"");
        String headers = jdbc.sql("select headers::text from outbox").query(String.class).single();
        assertThat(headers).contains(set.id() + ":0");
    }

    @Test
    void ingestingTheSameVersionPairTwiceIsADuplicate() {
        ChangeSet first = set("acme-api", "1", "2", 2);
        ChangeSet again = set("acme-api", "1", "2", 2);
        ingestion.ingest(first);
        ChangeIngestion.IngestResult result = ingestion.ingest(again);
        assertThat(result.status()).isEqualTo(ChangeIngestion.Status.DUPLICATE);
        assertThat(result.changeSetId()).isEqualTo(first.id());
        assertThat(count("change_set")).isEqualTo(1);
        assertThat(count("change_event")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void differentVersionPairsAreIndependent() {
        ingestion.ingest(set("acme-api", "1", "2", 1));
        ingestion.ingest(set("acme-api", "2", "3", 1));
        assertThat(count("change_set")).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(2);
    }

    @Test
    void concurrentIngestionOfOneVersionPairCreatesExactlyOneSet() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<ChangeIngestion.IngestResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                ChangeSet candidate = set("acme-api", "5", "6", 4);
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return ingestion.ingest(candidate);
                                }));
            }
            start.countDown();
            int created = 0;
            Map<UUID, Integer> ids = new HashMap<>();
            for (Future<ChangeIngestion.IngestResult> future : futures) {
                ChangeIngestion.IngestResult result = future.get();
                if (result.status() == ChangeIngestion.Status.CREATED) {
                    created++;
                }
                ids.merge(result.changeSetId(), 1, Integer::sum);
            }
            assertThat(created).isEqualTo(1);
            assertThat(ids).hasSize(1);
        }
        assertThat(count("change_set")).isEqualTo(1);
        assertThat(count("change_event")).isEqualTo(4);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void loadRebuildsTheOriginalSet() {
        ChangeSet original = set("acme-api", "1", "2", 3);
        ingestion.ingest(original);
        ChangeSet loaded = ingestion.load(original.id()).orElseThrow();
        assertThat(loaded.events()).isEqualTo(original.events());
        assertThat(loaded.notes()).isEqualTo(original.notes());
        assertThat(loaded.fromVersion()).isEqualTo("1");
        assertThat(loaded.sourceKind()).isEqualTo(SourceKind.OPENAPI);
        assertThat(loaded.detectedAt()).isEqualTo(original.detectedAt());
        assertThat(ingestion.load(UUID.randomUUID())).isEmpty();
    }

    @Test
    void replayBumpsTheCounterAndPublishesAgain() {
        ChangeSet original = set("acme-api", "1", "2", 1);
        ingestion.ingest(original);
        assertThat(ingestion.replay(original.id())).isEqualTo(1);
        assertThat(ingestion.replay(original.id())).isEqualTo(2);
        assertThat(count("outbox")).isEqualTo(3);
        List<String> types =
                jdbc.sql("select headers::text from outbox order by id").query(String.class).list();
        assertThat(types.get(0)).contains("change-set.created");
        assertThat(types.get(2)).contains("change-set.replayed").contains(original.id() + ":2");
        assertThat(count("change_event")).isEqualTo(1);
    }

    @Test
    void replayingAnUnknownSetIsNotFound() {
        assertThatThrownBy(() -> ingestion.replay(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        RookException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(count("outbox")).isZero();
    }

    private static Snapshot snapshot(String sourceId, String version, String content) {
        return new Snapshot(
                Ids.newId(),
                sourceId,
                version,
                Hashes.sha256Hex(content),
                content.getBytes(StandardCharsets.UTF_8),
                CLOCK.instant());
    }

    @Test
    void applyStoresSnapshotsSetsAndSourceStateTogether() {
        WatchedSource source = sources.find("acme-api").orElseThrow();
        PollOutcome outcome =
                PollOutcome.changed(
                        List.of(set("acme-api", "1", "2", 2)),
                        List.of(snapshot("acme-api", "2", "spec-2")),
                        "\"etag-2\"",
                        "2");
        ingestion.apply(source, outcome, snapshots);
        assertThat(count("source_snapshot")).isEqualTo(1);
        assertThat(count("change_set")).isEqualTo(1);
        WatchedSource after = sources.find("acme-api").orElseThrow();
        assertThat(after.etag()).isEqualTo("\"etag-2\"");
        assertThat(after.lastVersion()).isEqualTo("2");
        assertThat(after.lastCheckedAt()).isEqualTo(CLOCK.instant());
        assertThat(after.lastError()).isNull();
    }

    @Test
    void applyRollsEverythingBackWhenALaterSetFails() {
        WatchedSource source = sources.find("acme-api").orElseThrow();
        PollOutcome outcome =
                PollOutcome.changed(
                        List.of(set("acme-api", "1", "2", 1), set("missing-source", "1", "2", 1)),
                        List.of(snapshot("acme-api", "2", "spec-2")),
                        "\"etag-2\"",
                        "2");
        assertThatThrownBy(() -> ingestion.apply(source, outcome, snapshots))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("source_snapshot")).isZero();
        assertThat(count("change_set")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(sources.find("acme-api").orElseThrow().lastVersion()).isNull();
    }

    @Test
    void failuresAreRecordedAndTruncated() {
        ingestion.recordFailure("acme-api", "x".repeat(5000));
        WatchedSource after = sources.find("acme-api").orElseThrow();
        assertThat(after.lastError()).hasSize(2000);
        assertThat(after.lastCheckedAt()).isEqualTo(CLOCK.instant());
    }
}
