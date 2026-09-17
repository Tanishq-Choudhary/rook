package dev.rook.control.audit;

import dev.rook.core.hash.Hashes;
import dev.rook.core.json.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

public class AuditLog {

    public static final String GENESIS = "0".repeat(64);

    private static final long LOCK_KEY = 0x524F4F4B41554449L;
    private static final int BATCH = 500;

    public record Entry(
            long seq,
            Instant at,
            String actor,
            String action,
            String subject,
            JsonNode data,
            String previousHash,
            String hash) {}

    public record Verification(boolean intact, long checked, Long firstBadSeq, String problem) {

        static Verification ok(long checked) {
            return new Verification(true, checked, null, "");
        }

        static Verification broken(long checked, long seq, String problem) {
            return new Verification(false, checked, seq, problem);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AuditLog(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public Entry append(String actor, String action, String subject, Map<String, ?> data) {
        if (actor == null || actor.isBlank() || action == null || action.isBlank()) {
            throw new IllegalArgumentException("actor and action are required");
        }
        JsonNode payload = Json.tree(data == null ? Map.of() : data);
        String canonical = Canonical.of(payload);
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return tx.execute(
                status -> {
                    jdbc.sql("select pg_advisory_xact_lock(:key)")
                            .param("key", LOCK_KEY)
                            .query()
                            .listOfRows();
                    String previous =
                            jdbc.sql("select hash from audit_log order by seq desc limit 1")
                                    .query(String.class)
                                    .optional()
                                    .orElse(GENESIS);
                    String hash = hash(previous, at, actor, action, subject, canonical);
                    long seq =
                            jdbc.sql(
                                            """
                                            insert into audit_log (at, actor, action, subject, data, prev_hash, hash)
                                            values (:at, :actor, :action, :subject, cast(:data as jsonb), :prev, :hash)
                                            returning seq
                                            """)
                                    .param("at", Timestamp.from(at))
                                    .param("actor", actor)
                                    .param("action", action)
                                    .param("subject", subject)
                                    .param("data", canonical)
                                    .param("prev", previous)
                                    .param("hash", hash)
                                    .query(Long.class)
                                    .single();
                    return new Entry(seq, at, actor, action, subject, payload, previous, hash);
                });
    }

    public String head() {
        return jdbc.sql("select hash from audit_log order by seq desc limit 1")
                .query(String.class)
                .optional()
                .orElse(GENESIS);
    }

    public List<Entry> forSubject(String subject, int limit) {
        return jdbc.sql(
                        """
                        select seq, at, actor, action, subject, data, prev_hash, hash
                        from audit_log where subject = :subject order by seq limit :limit
                        """)
                .param("subject", subject)
                .param("limit", limit)
                .query((rs, row) -> entry(rs))
                .list();
    }

    public Verification verify() {
        return verify(null);
    }

    public Verification verify(String expectedHead) {
        String previous = GENESIS;
        long checked = 0;
        long after = 0;
        while (true) {
            List<Entry> batch =
                    jdbc.sql(
                                    """
                                    select seq, at, actor, action, subject, data, prev_hash, hash
                                    from audit_log where seq > :after order by seq limit :limit
                                    """)
                            .param("after", after)
                            .param("limit", BATCH)
                            .query((rs, row) -> entry(rs))
                            .list();
            if (batch.isEmpty()) {
                break;
            }
            for (Entry entry : batch) {
                checked++;
                if (!entry.previousHash().equals(previous)) {
                    return Verification.broken(
                            checked, entry.seq(), "the link to the previous entry is broken");
                }
                String expected =
                        hash(
                                entry.previousHash(),
                                entry.at(),
                                entry.actor(),
                                entry.action(),
                                entry.subject(),
                                Canonical.of(entry.data()));
                if (!Hashes.constantTimeEquals(expected, entry.hash())) {
                    return Verification.broken(
                            checked, entry.seq(), "the entry does not match its hash");
                }
                previous = entry.hash();
                after = entry.seq();
            }
        }
        if (expectedHead != null && !expectedHead.equals(previous)) {
            return Verification.broken(
                    checked, after, "the newest entries differ from the recorded head");
        }
        return Verification.ok(checked);
    }

    static String hash(
            String previous, Instant at, String actor, String action, String subject, String data) {
        return Hashes.sha256Hex(
                previous,
                Long.toString(at.getEpochSecond()) + "." + at.getNano(),
                actor,
                action,
                subject,
                data);
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(
                rs.getLong("seq"),
                rs.getTimestamp("at").toInstant(),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("subject"),
                Json.tree(rs.getString("data")),
                rs.getString("prev_hash"),
                rs.getString("hash"));
    }
}
