package dev.rook.changes.store;

import dev.rook.changes.source.PollOutcome;
import dev.rook.changes.source.Snapshot;
import dev.rook.changes.source.SnapshotStore;
import dev.rook.changes.source.WatchedSource;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.MigrationNote;
import dev.rook.core.change.Severity;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.error.RookException;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import dev.rook.db.Outbox;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

public class ChangeIngestion {

    public static final String TOPIC = "rook.change-sets";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ChangeIngestion(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public enum Status {
        CREATED,
        DUPLICATE
    }

    public record IngestResult(Status status, UUID changeSetId) {}

    public IngestResult ingest(ChangeSet set) {
        return tx.execute(status -> ingestInTransaction(set));
    }

    public void apply(WatchedSource source, PollOutcome outcome, SnapshotStore snapshots) {
        tx.executeWithoutResult(
                status -> {
                    for (Snapshot snapshot : outcome.snapshots()) {
                        snapshots.save(snapshot);
                    }
                    for (ChangeSet set : outcome.changeSets()) {
                        ingestInTransaction(set);
                    }
                    jdbc.sql(
                                    """
                                    update watched_source
                                    set etag = :etag, last_version = :version,
                                        last_checked_at = :now, last_error = null
                                    where id = :id
                                    """)
                            .param("etag", outcome.etag())
                            .param("version", outcome.lastVersion())
                            .param("now", Timestamp.from(clock.instant()))
                            .param("id", source.id())
                            .update();
                });
    }

    public void recordFailure(String sourceId, String message) {
        String trimmed = message == null ? "unknown failure" : message;
        if (trimmed.length() > 2000) {
            trimmed = trimmed.substring(0, 2000);
        }
        jdbc.sql(
                        "update watched_source set last_error = :error, last_checked_at = :now where id = :id")
                .param("error", trimmed)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", sourceId)
                .update();
    }

    public int replay(UUID changeSetId) {
        Integer count =
                tx.execute(
                        status -> {
                            Optional<Integer> bumped =
                                    jdbc.sql(
                                                    """
                                                    update change_set set replay_count = replay_count + 1
                                                    where id = :id returning replay_count
                                                    """)
                                            .param("id", changeSetId)
                                            .query(Integer.class)
                                            .optional();
                            if (bumped.isEmpty()) {
                                throw RookException.notFound("change set", changeSetId);
                            }
                            ChangeSet set = load(changeSetId).orElseThrow();
                            publish(set, bumped.get());
                            return bumped.get();
                        });
        return count == null ? 0 : count;
    }

    public Optional<ChangeSet> load(UUID id) {
        Optional<ChangeSetRow> row =
                jdbc.sql(
                                """
                                select id, source_id, from_version, to_version, notes, detected_at,
                                       (select kind from watched_source w where w.id = c.source_id) as source_kind
                                from change_set c where id = :id
                                """)
                        .param("id", id)
                        .query(
                                (rs, n) ->
                                        new ChangeSetRow(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("source_id"),
                                                SourceKind.valueOf(rs.getString("source_kind")),
                                                rs.getString("from_version"),
                                                rs.getString("to_version"),
                                                rs.getString("notes"),
                                                rs.getTimestamp("detected_at").toInstant()))
                        .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        ChangeSetRow r = row.get();
        List<ChangeEvent> events =
                jdbc.sql(
                                """
                                select fingerprint, kind, severity, subject_kind, subject_name, attributes, summary
                                from change_event where change_set_id = :id
                                """)
                        .param("id", id)
                        .query(
                                (rs, n) ->
                                        new ChangeEvent(
                                                rs.getString("fingerprint"),
                                                ChangeKind.valueOf(rs.getString("kind")),
                                                Severity.valueOf(rs.getString("severity")),
                                                new SymbolRef(
                                                        SymbolKind.valueOf(
                                                                rs.getString("subject_kind")),
                                                        rs.getString("subject_name")),
                                                Json.read(
                                                        rs.getString("attributes"),
                                                        new TypeReference<
                                                                Map<String, String>>() {}),
                                                rs.getString("summary")))
                        .list();
        List<MigrationNote> notes =
                Json.read(r.notes(), new TypeReference<List<MigrationNote>>() {});
        return Optional.of(
                new ChangeSet(
                        r.id(),
                        r.sourceId(),
                        r.kind(),
                        r.from(),
                        r.to(),
                        events,
                        notes,
                        r.detectedAt()));
    }

    private IngestResult ingestInTransaction(ChangeSet set) {
        int inserted =
                jdbc.sql(
                                """
                                insert into change_set (id, source_id, change_key, from_version, to_version,
                                  highest_severity, requires_migration, notes, detected_at)
                                values (:id, :source, :key, :from, :to, :severity, :migrate,
                                  cast(:notes as jsonb), :detected)
                                on conflict (change_key) do nothing
                                """)
                        .param("id", set.id())
                        .param("source", set.sourceId())
                        .param("key", set.key())
                        .param("from", set.fromVersion())
                        .param("to", set.toVersion())
                        .param("severity", set.highestSeverity().name())
                        .param("migrate", set.requiresMigration())
                        .param("notes", Json.write(set.notes()))
                        .param("detected", Timestamp.from(set.detectedAt()))
                        .update();
        if (inserted == 0) {
            UUID existing =
                    jdbc.sql("select id from change_set where change_key = :key")
                            .param("key", set.key())
                            .query(UUID.class)
                            .single();
            return new IngestResult(Status.DUPLICATE, existing);
        }
        for (ChangeEvent event : set.events()) {
            jdbc.sql(
                            """
                            insert into change_event (id, change_set_id, fingerprint, kind, severity,
                              subject_kind, subject_name, attributes, summary)
                            values (:id, :set, :fingerprint, :kind, :severity, :subjectKind, :subjectName,
                              cast(:attributes as jsonb), :summary)
                            on conflict (fingerprint) do nothing
                            """)
                    .param("id", Ids.newId())
                    .param("set", set.id())
                    .param("fingerprint", event.fingerprint())
                    .param("kind", event.kind().name())
                    .param("severity", event.severity().name())
                    .param("subjectKind", event.subject().kind().name())
                    .param("subjectName", event.subject().name())
                    .param("attributes", Json.write(event.attributes()))
                    .param("summary", event.summary())
                    .update();
        }
        publish(set, 0);
        return new IngestResult(Status.CREATED, set.id());
    }

    private void publish(ChangeSet set, int replay) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changeSetId", set.id());
        payload.put("sourceId", set.sourceId());
        payload.put("sourceKind", set.sourceKind());
        payload.put("fromVersion", set.fromVersion());
        payload.put("toVersion", set.toVersion());
        payload.put("highestSeverity", set.highestSeverity());
        payload.put("requiresMigration", set.requiresMigration());
        payload.put("eventCount", set.events().size());
        payload.put("replay", replay);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("messageId", set.id() + ":" + replay);
        headers.put("type", replay == 0 ? "change-set.created" : "change-set.replayed");
        Outbox.append(jdbc, TOPIC, set.sourceId(), Json.write(payload), headers);
    }

    private record ChangeSetRow(
            UUID id,
            String sourceId,
            SourceKind kind,
            String from,
            String to,
            String notes,
            Instant detectedAt) {}
}
