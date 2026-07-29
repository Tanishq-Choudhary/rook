package dev.rook.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class SchemaIT {

    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcClient jdbc;
    static DataSource dataSource;
    static MigrateResult result;

    @BeforeAll
    static void migrate() {
        dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        result = Migrations.migrate(dataSource);
        jdbc = JdbcClient.create(dataSource);
    }

    private static void source(String id) {
        jdbc.sql(
                        "insert into watched_source (id, kind, display_name, location) values (:id, 'OPENAPI', :id, 'http://x')")
                .param("id", id)
                .update();
    }

    private static UUID changeSet(String sourceId, String key) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                        insert into change_set (id, source_id, change_key, from_version, to_version,
                          highest_severity, requires_migration, detected_at)
                        values (:id, :source, :key, '1', '2', 'BREAKING', true, now())
                        """)
                .param("id", id)
                .param("source", sourceId)
                .param("key", key)
                .update();
        return id;
    }

    private static void event(UUID setId, String fingerprint) {
        jdbc.sql(
                        """
                        insert into change_event (id, change_set_id, fingerprint, kind, severity,
                          subject_kind, subject_name, summary)
                        values (:id, :set, :fp, 'METHOD_REMOVED', 'BREAKING', 'JAVA_METHOD', 'a.B#c()', 'gone')
                        """)
                .param("id", UUID.randomUUID())
                .param("set", setId)
                .param("fp", fingerprint)
                .update();
    }

    @Test
    void migrationsApplyCleanly() {
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);
    }

    @Test
    void runningMigrationsTwiceIsANoOp() {
        assertThat(Migrations.migrate(dataSource).migrationsExecuted).isZero();
    }

    @Test
    void sourceKindIsConstrained() {
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "insert into watched_source (id, kind, display_name, location) values ('bad', 'FTP', 'x', 'y')")
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pollIntervalHasAFloor() {
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "insert into watched_source (id, kind, display_name, location, poll_interval_seconds) values ('fast', 'OPENAPI', 'x', 'y', 5)")
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void changeKeyIsUnique() {
        source("src-key");
        changeSet("src-key", "key-1");
        assertThatThrownBy(() -> changeSet("src-key", "key-1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void fingerprintIsGloballyUnique() {
        source("src-fp");
        UUID first = changeSet("src-fp", "fp-set-1");
        UUID second = changeSet("src-fp", "fp-set-2");
        event(first, "fp-1");
        assertThatThrownBy(() -> event(second, "fp-1")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deletingAChangeSetRemovesItsEvents() {
        source("src-cascade");
        UUID set = changeSet("src-cascade", "cascade-1");
        event(set, "cascade-fp");
        jdbc.sql("delete from change_set where id = :id").param("id", set).update();
        Integer remaining =
                jdbc.sql("select count(*) from change_event where fingerprint = 'cascade-fp'")
                        .query(Integer.class)
                        .single();
        assertThat(remaining).isZero();
    }

    @Test
    void snapshotsAreUniquePerSourceVersion() {
        source("src-snap");
        String insert =
                "insert into source_snapshot (id, source_id, version, content_hash, content_gz) values (:id, 'src-snap', :v, :h, '\\x00')";
        jdbc.sql(insert).param("id", UUID.randomUUID()).param("v", "1").param("h", "h1").update();
        assertThatThrownBy(
                        () ->
                                jdbc.sql(insert)
                                        .param("id", UUID.randomUUID())
                                        .param("v", "1")
                                        .param("h", "h2")
                                        .update())
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void processedMessageRejectsDuplicates() {
        String sql =
                "insert into processed_message (consumer, message_id) values ('fanout', 'm-1') on conflict do nothing";
        assertThat(jdbc.sql(sql).update()).isEqualTo(1);
        assertThat(jdbc.sql(sql).update()).isZero();
    }

    @Test
    void outboxRowsGetAscendingIdsAndStartUnpublished() {
        jdbc.sql("insert into outbox (topic, message_key, payload) values ('t', 'k', '{}'::jsonb)")
                .update();
        jdbc.sql("insert into outbox (topic, message_key, payload) values ('t', 'k', '{}'::jsonb)")
                .update();
        var ids =
                jdbc.sql("select id from outbox where published_at is null order by id")
                        .query(Long.class)
                        .list();
        assertThat(ids).hasSizeGreaterThanOrEqualTo(2).isSorted();
    }

    @Test
    void unpublishedOutboxIndexExists() {
        Integer found =
                jdbc.sql("select count(*) from pg_indexes where indexname = 'outbox_unpublished'")
                        .query(Integer.class)
                        .single();
        assertThat(found).isEqualTo(1);
    }
}
