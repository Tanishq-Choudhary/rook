package dev.rook.pipeline.store;

import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

public class ArtifactStore {

    public record StoredImpact(
            UUID jobId, String decision, String summary, String baseSha, ImpactReport report) {}

    public record NewProposal(
            UUID jobId,
            int attempt,
            String strategy,
            String status,
            String summary,
            Double confidence,
            String diff,
            Map<String, String> files,
            List<String> deletions,
            List<String> unresolved,
            Map<String, Object> usage,
            long costMicros,
            List<?> trace) {}

    public record StoredProposal(
            UUID id,
            UUID jobId,
            int attempt,
            String strategy,
            String status,
            String summary,
            Double confidence,
            String diff,
            Map<String, String> files,
            List<String> deletions,
            List<String> unresolved,
            long costMicros,
            JsonNode trace,
            Instant createdAt) {}

    public record NewVerification(
            UUID jobId,
            int attempt,
            boolean passed,
            boolean supported,
            String summary,
            Integer baselineRun,
            Integer baselineFailed,
            Integer patchedRun,
            Integer patchedFailed,
            Map<String, Object> report,
            long elapsedMillis) {}

    public record StoredVerification(
            UUID id,
            UUID jobId,
            int attempt,
            boolean passed,
            boolean supported,
            String summary,
            Integer baselineRun,
            Integer baselineFailed,
            Integer patchedRun,
            Integer patchedFailed,
            JsonNode report,
            long elapsedMillis,
            Instant createdAt) {}

    private final JdbcClient jdbc;

    public ArtifactStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void saveImpact(
            UUID jobId, String decision, String summary, String baseSha, ImpactReport report) {
        jdbc.sql(
                        """
                        insert into impact_report (job_id, decision, summary, base_sha, report)
                        values (:job, :decision, :summary, :sha, cast(:report as jsonb))
                        on conflict (job_id) do update
                          set decision = excluded.decision, summary = excluded.summary,
                              base_sha = excluded.base_sha, report = excluded.report,
                              created_at = now()
                        """)
                .param("job", jobId)
                .param("decision", decision)
                .param("summary", summary)
                .param("sha", baseSha)
                .param("report", Json.write(report))
                .update();
    }

    public Optional<StoredImpact> impact(UUID jobId) {
        return jdbc.sql(
                        "select job_id, decision, summary, base_sha, report::text as report"
                                + " from impact_report where job_id = :job")
                .param("job", jobId)
                .query(
                        (rs, row) ->
                                new StoredImpact(
                                        rs.getObject("job_id", UUID.class),
                                        rs.getString("decision"),
                                        rs.getString("summary"),
                                        rs.getString("base_sha"),
                                        Json.read(rs.getString("report"), ImpactReport.class)))
                .optional();
    }

    public UUID saveProposal(NewProposal proposal) {
        UUID id = Ids.newId();
        jdbc.sql(
                        """
                        insert into patch_proposal (id, job_id, attempt, strategy, status, summary, confidence,
                          diff, files, deletions, unresolved, usage, cost_micros, trace)
                        values (:id, :job, :attempt, :strategy, :status, :summary, :confidence, :diff,
                          cast(:files as jsonb), cast(:deletions as jsonb), cast(:unresolved as jsonb),
                          cast(:usage as jsonb), :cost, cast(:trace as jsonb))
                        on conflict (job_id, attempt) do update
                          set strategy = excluded.strategy, status = excluded.status, summary = excluded.summary,
                              confidence = excluded.confidence, diff = excluded.diff, files = excluded.files,
                              deletions = excluded.deletions, unresolved = excluded.unresolved,
                              usage = excluded.usage, cost_micros = excluded.cost_micros, trace = excluded.trace
                        """)
                .param("id", id)
                .param("job", proposal.jobId())
                .param("attempt", proposal.attempt())
                .param("strategy", proposal.strategy())
                .param("status", proposal.status())
                .param("summary", proposal.summary())
                .param(
                        "confidence",
                        proposal.confidence() == null
                                ? null
                                : BigDecimal.valueOf(proposal.confidence()))
                .param("diff", proposal.diff())
                .param("files", Json.write(proposal.files()))
                .param("deletions", Json.write(proposal.deletions()))
                .param("unresolved", Json.write(proposal.unresolved()))
                .param("usage", Json.write(proposal.usage()))
                .param("cost", proposal.costMicros())
                .param("trace", Json.write(proposal.trace()))
                .update();
        return latestProposal(proposal.jobId()).orElseThrow().id();
    }

    public Optional<StoredProposal> latestProposal(UUID jobId) {
        return jdbc.sql(
                        """
                        select id, job_id, attempt, strategy, status, summary, confidence, diff,
                               files::text as files, deletions::text as deletions,
                               unresolved::text as unresolved, cost_micros, trace::text as trace, created_at
                        from patch_proposal where job_id = :job order by attempt desc limit 1
                        """)
                .param("job", jobId)
                .query((rs, row) -> proposal(rs))
                .optional();
    }

    public void saveVerification(NewVerification run) {
        jdbc.sql(
                        """
                        insert into verification_run (id, job_id, attempt, passed, supported, summary,
                          baseline_run, baseline_failed, patched_run, patched_failed, report, elapsed_millis)
                        values (:id, :job, :attempt, :passed, :supported, :summary, :baselineRun, :baselineFailed,
                          :patchedRun, :patchedFailed, cast(:report as jsonb), :elapsed)
                        on conflict (job_id, attempt) do update
                          set passed = excluded.passed, supported = excluded.supported, summary = excluded.summary,
                              baseline_run = excluded.baseline_run, baseline_failed = excluded.baseline_failed,
                              patched_run = excluded.patched_run, patched_failed = excluded.patched_failed,
                              report = excluded.report, elapsed_millis = excluded.elapsed_millis
                        """)
                .param("id", Ids.newId())
                .param("job", run.jobId())
                .param("attempt", run.attempt())
                .param("passed", run.passed())
                .param("supported", run.supported())
                .param("summary", run.summary())
                .param("baselineRun", run.baselineRun())
                .param("baselineFailed", run.baselineFailed())
                .param("patchedRun", run.patchedRun())
                .param("patchedFailed", run.patchedFailed())
                .param("report", Json.write(run.report()))
                .param("elapsed", run.elapsedMillis())
                .update();
    }

    public Optional<StoredVerification> latestVerification(UUID jobId) {
        return jdbc.sql(
                        """
                        select id, job_id, attempt, passed, supported, summary, baseline_run, baseline_failed,
                               patched_run, patched_failed, report::text as report, elapsed_millis, created_at
                        from verification_run where job_id = :job order by attempt desc limit 1
                        """)
                .param("job", jobId)
                .query((rs, row) -> verification(rs))
                .optional();
    }

    private static StoredProposal proposal(ResultSet rs) throws SQLException {
        BigDecimal confidence = rs.getBigDecimal("confidence");
        return new StoredProposal(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt"),
                rs.getString("strategy"),
                rs.getString("status"),
                rs.getString("summary"),
                confidence == null ? null : confidence.doubleValue(),
                rs.getString("diff"),
                Json.read(rs.getString("files"), new TypeReference<Map<String, String>>() {}),
                Json.read(rs.getString("deletions"), new TypeReference<List<String>>() {}),
                Json.read(rs.getString("unresolved"), new TypeReference<List<String>>() {}),
                rs.getLong("cost_micros"),
                Json.tree(rs.getString("trace")),
                rs.getTimestamp("created_at").toInstant());
    }

    private static StoredVerification verification(ResultSet rs) throws SQLException {
        return new StoredVerification(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getInt("attempt"),
                rs.getBoolean("passed"),
                rs.getBoolean("supported"),
                rs.getString("summary"),
                (Integer) rs.getObject("baseline_run"),
                (Integer) rs.getObject("baseline_failed"),
                (Integer) rs.getObject("patched_run"),
                (Integer) rs.getObject("patched_failed"),
                Json.tree(rs.getString("report")),
                rs.getLong("elapsed_millis"),
                rs.getTimestamp("created_at").toInstant());
    }
}
