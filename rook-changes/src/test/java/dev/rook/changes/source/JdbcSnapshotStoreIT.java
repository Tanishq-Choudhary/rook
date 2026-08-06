package dev.rook.changes.source;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.hash.Hashes;
import dev.rook.core.id.Ids;
import dev.rook.testkit.TestDatabases;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcSnapshotStoreIT {

    private JdbcClient jdbc;
    private JdbcSnapshotStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = TestDatabases.fresh();
        jdbc = JdbcClient.create(ds);
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location) values ('acme', 'OPENAPI', 'Acme', 'http://x')")
                .update();
        store = new JdbcSnapshotStore(jdbc);
    }

    private static Snapshot snapshot(String version, String content) {
        return new Snapshot(
                Ids.newId(),
                "acme",
                version,
                Hashes.sha256Hex(content),
                content.getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-08-09T10:00:00Z"));
    }

    @Test
    void savedSnapshotsRoundTripThroughCompression() {
        Snapshot original = snapshot("1.0.0", "openapi: 3.0.0\n".repeat(500));
        assertThat(store.save(original)).isTrue();
        Optional<Snapshot> found = store.byVersion("acme", "1.0.0");
        assertThat(found).isPresent();
        assertThat(found.get().content()).isEqualTo(original.content());
        assertThat(found.get().contentHash()).isEqualTo(original.contentHash());
        assertThat(found.get().fetchedAt()).isEqualTo(original.fetchedAt());
    }

    @Test
    void contentIsStoredCompressed() {
        String content = "a".repeat(100_000);
        store.save(snapshot("1.0.0", content));
        Integer size =
                jdbc.sql("select length(content_gz) from source_snapshot where version = '1.0.0'")
                        .query(Integer.class)
                        .single();
        assertThat(size).isLessThan(2_000);
    }

    @Test
    void lookupByHashFindsTheSameSnapshot() {
        Snapshot original = snapshot("1.0.0", "content");
        store.save(original);
        assertThat(store.byHash("acme", original.contentHash()))
                .get()
                .extracting(Snapshot::version)
                .isEqualTo("1.0.0");
        assertThat(store.byHash("acme", "nope")).isEmpty();
        assertThat(store.byVersion("acme", "9.9.9")).isEmpty();
    }

    @Test
    void duplicateVersionsOrHashesAreIgnored() {
        assertThat(store.save(snapshot("1.0.0", "one"))).isTrue();
        assertThat(store.save(snapshot("1.0.0", "two"))).isFalse();
        assertThat(store.save(snapshot("1.0.1", "one"))).isFalse();
        Integer count =
                jdbc.sql("select count(*) from source_snapshot").query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }
}
