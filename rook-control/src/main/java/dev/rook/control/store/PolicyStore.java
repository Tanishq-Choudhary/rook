package dev.rook.control.store;

import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Policy;
import dev.rook.control.policy.Reason;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

public class PolicyStore {

    public record Stored(
            UUID id,
            UUID installationId,
            UUID repositoryId,
            int version,
            Policy policy,
            Instant createdAt) {}

    public record StoredDecision(
            UUID jobId, Decision decision, Integer policyVersion, Instant createdAt) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public PolicyStore(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Stored save(UUID installationId, UUID repositoryId, Policy policy) {
        return tx.execute(
                status -> {
                    jdbc.sql("select id from installation where id = :id for update")
                            .param("id", installationId)
                            .query(UUID.class)
                            .optional()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "unknown installation " + installationId));
                    int version =
                            jdbc.sql(
                                            """
                                            select coalesce(max(version), 0) + 1 from policy
                                            where installation_id = :installation
                                              and repository_id is not distinct from :repository
                                            """)
                                    .param("installation", installationId)
                                    .param("repository", repositoryId)
                                    .query(Integer.class)
                                    .single();
                    UUID id = Ids.newId();
                    jdbc.sql(
                                    """
                                    insert into policy (id, installation_id, repository_id, version, rules)
                                    values (:id, :installation, :repository, :version, cast(:rules as jsonb))
                                    """)
                            .param("id", id)
                            .param("installation", installationId)
                            .param("repository", repositoryId)
                            .param("version", version)
                            .param("rules", Json.write(policy))
                            .update();
                    return find(id).orElseThrow();
                });
    }

    public Optional<Stored> find(UUID id) {
        return jdbc.sql(
                        "select id, installation_id, repository_id, version, rules, created_at"
                                + " from policy where id = :id")
                .param("id", id)
                .query((rs, row) -> stored(rs))
                .optional();
    }

    public Optional<Stored> latest(UUID installationId, UUID repositoryId) {
        return jdbc.sql(
                        """
                        select id, installation_id, repository_id, version, rules, created_at
                        from policy
                        where installation_id = :installation
                          and repository_id is not distinct from :repository
                        order by version desc limit 1
                        """)
                .param("installation", installationId)
                .param("repository", repositoryId)
                .query((rs, row) -> stored(rs))
                .optional();
    }

    public Stored effective(UUID installationId, UUID repositoryId) {
        Optional<Stored> repository =
                repositoryId == null ? Optional.empty() : latest(installationId, repositoryId);
        if (repository.isPresent()) {
            return repository.get();
        }
        return latest(installationId, null)
                .orElseGet(
                        () ->
                                new Stored(
                                        null,
                                        installationId,
                                        null,
                                        0,
                                        Policy.standard(),
                                        Instant.EPOCH));
    }

    public void recordDecision(UUID jobId, Decision decision, int policyVersion) {
        jdbc.sql(
                        """
                        insert into policy_decision (job_id, outcome, reasons, policy_version)
                        values (:job, :outcome, cast(:reasons as jsonb), :version)
                        """)
                .param("job", jobId)
                .param("outcome", decision.outcome().name())
                .param("reasons", Json.write(decision.reasons()))
                .param("version", policyVersion)
                .update();
    }

    public Optional<StoredDecision> latestDecision(UUID jobId) {
        return jdbc.sql(
                        """
                        select job_id, outcome, reasons, policy_version, created_at
                        from policy_decision where job_id = :job order by id desc limit 1
                        """)
                .param("job", jobId)
                .query(
                        (rs, row) ->
                                new StoredDecision(
                                        rs.getObject("job_id", UUID.class),
                                        new Decision(
                                                Decision.Outcome.valueOf(rs.getString("outcome")),
                                                reasons(rs.getString("reasons"))),
                                        (Integer) rs.getObject("policy_version"),
                                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    static List<Reason> reasons(String json) {
        return Json.read(json, new TypeReference<List<Reason>>() {});
    }

    private static Stored stored(ResultSet rs) throws SQLException {
        return new Stored(
                rs.getObject("id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getInt("version"),
                Json.read(rs.getString("rules"), Policy.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
