package dev.rook.control.store;

import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Reason;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class ApprovalStore {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    public record Approval(
            UUID id,
            UUID jobId,
            Status status,
            List<Reason> reasons,
            Instant requestedAt,
            String decidedBy,
            Instant decidedAt,
            String note) {

        public Approval {
            reasons = List.copyOf(reasons);
        }
    }

    private static final String COLUMNS =
            "id, job_id, status, reasons, requested_at, decided_by, decided_at, note";

    private final JdbcClient jdbc;
    private final Clock clock;

    public ApprovalStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Approval request(UUID jobId, Decision decision) {
        jdbc.sql(
                        """
                        insert into approval (id, job_id, reasons, requested_at)
                        values (:id, :job, cast(:reasons as jsonb), :now)
                        on conflict (job_id) do nothing
                        """)
                .param("id", Ids.newId())
                .param("job", jobId)
                .param("reasons", Json.write(decision.reasons()))
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return find(jobId).orElseThrow();
    }

    public Optional<Approval> find(UUID jobId) {
        return jdbc.sql("select " + COLUMNS + " from approval where job_id = :job")
                .param("job", jobId)
                .query((rs, row) -> approval(rs))
                .optional();
    }

    public boolean decide(UUID jobId, boolean approved, String actor, String note) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("a reviewer is required");
        }
        return jdbc.sql(
                                """
                                update approval set status = :status, decided_by = :actor,
                                  decided_at = :now, note = :note
                                where job_id = :job and status = 'PENDING'
                                """)
                        .param("status", approved ? "APPROVED" : "REJECTED")
                        .param("actor", actor)
                        .param("now", Timestamp.from(clock.instant()))
                        .param("note", note)
                        .param("job", jobId)
                        .update()
                > 0;
    }

    public List<Approval> pending(UUID installationId, int limit) {
        return jdbc.sql(
                        """
                        select a.id, a.job_id, a.status, a.reasons, a.requested_at, a.decided_by,
                               a.decided_at, a.note
                        from approval a
                          join migration_job j on j.id = a.job_id
                          join repository r on r.id = j.repository_id
                        where a.status = 'PENDING' and r.installation_id = :installation
                        order by a.requested_at limit :limit
                        """)
                .param("installation", installationId)
                .param("limit", limit)
                .query((rs, row) -> approval(rs))
                .list();
    }

    private static Approval approval(ResultSet rs) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new Approval(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                Status.valueOf(rs.getString("status")),
                PolicyStore.reasons(rs.getString("reasons")),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getString("note"));
    }
}
