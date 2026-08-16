package dev.rook.workflow.repo;

import dev.rook.core.error.RookException;
import dev.rook.core.id.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class RepositoryStore {

    public record Installation(
            UUID id,
            Long githubInstallationId,
            String accountLogin,
            String status,
            Instant createdAt) {}

    public record Repository(
            UUID id,
            UUID installationId,
            Long githubRepoId,
            String fullName,
            String defaultBranch,
            boolean enabled,
            String lastIndexedSha) {}

    private static final String REPOSITORY_COLUMNS =
            "r.id, r.installation_id, r.github_repo_id, r.full_name, r.default_branch, r.enabled,"
                    + " r.last_indexed_sha";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public RepositoryStore(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Installation upsertInstallation(long githubInstallationId, String accountLogin) {
        return jdbc.sql(
                        """
                        insert into installation (id, github_installation_id, account_login, status)
                        values (:id, :github, :login, 'ACTIVE')
                        on conflict (github_installation_id) do update
                          set account_login = excluded.account_login, status = 'ACTIVE'
                        returning id, github_installation_id, account_login, status, created_at
                        """)
                .param("id", Ids.newId())
                .param("github", githubInstallationId)
                .param("login", accountLogin)
                .query(RepositoryStore::installation)
                .single();
    }

    public void setInstallationStatus(long githubInstallationId, String status) {
        jdbc.sql("update installation set status = :status where github_installation_id = :github")
                .param("status", status)
                .param("github", githubInstallationId)
                .update();
    }

    public Optional<Installation> findInstallation(long githubInstallationId) {
        return jdbc.sql(
                        """
                        select id, github_installation_id, account_login, status, created_at
                        from installation where github_installation_id = :github
                        """)
                .param("github", githubInstallationId)
                .query(RepositoryStore::installation)
                .optional();
    }

    public Repository upsertRepository(
            UUID installationId, long githubRepoId, String fullName, String defaultBranch) {
        return jdbc.sql(
                        """
                        insert into repository (id, installation_id, github_repo_id, full_name, default_branch, enabled)
                        values (:id, :installation, :github, :name, :branch, true)
                        on conflict (github_repo_id) do update
                          set full_name = excluded.full_name,
                              default_branch = excluded.default_branch,
                              installation_id = excluded.installation_id,
                              enabled = true
                        returning id, installation_id, github_repo_id, full_name, default_branch, enabled,
                                  last_indexed_sha
                        """)
                .param("id", Ids.newId())
                .param("installation", installationId)
                .param("github", githubRepoId)
                .param("name", fullName)
                .param("branch", defaultBranch)
                .query(RepositoryStore::repository)
                .single();
    }

    public void disableRepository(long githubRepoId) {
        jdbc.sql("update repository set enabled = false where github_repo_id = :github")
                .param("github", githubRepoId)
                .update();
    }

    public Optional<Repository> find(UUID id) {
        return jdbc.sql("select " + REPOSITORY_COLUMNS + " from repository r where r.id = :id")
                .param("id", id)
                .query(RepositoryStore::repository)
                .optional();
    }

    public Optional<Repository> findByFullName(String fullName) {
        return jdbc.sql(
                        "select "
                                + REPOSITORY_COLUMNS
                                + " from repository r where r.full_name = :name and r.enabled order by r.created_at limit 1")
                .param("name", fullName)
                .query(RepositoryStore::repository)
                .optional();
    }

    public List<Repository> list(UUID installationId) {
        return jdbc.sql(
                        "select "
                                + REPOSITORY_COLUMNS
                                + " from repository r where r.installation_id = :installation order by r.full_name")
                .param("installation", installationId)
                .query(RepositoryStore::repository)
                .list();
    }

    public void markIndexed(UUID repositoryId, String sha) {
        jdbc.sql("update repository set last_indexed_sha = :sha where id = :id")
                .param("sha", sha)
                .param("id", repositoryId)
                .update();
    }

    public void watch(UUID repositoryId, String sourceId) {
        try {
            jdbc.sql(
                            """
                            insert into repository_watch (repository_id, source_id) values (:repository, :source)
                            on conflict do nothing
                            """)
                    .param("repository", repositoryId)
                    .param("source", sourceId)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw RookException.invalid("unknown repository or source", e);
        }
    }

    public void unwatch(UUID repositoryId, String sourceId) {
        jdbc.sql(
                        "delete from repository_watch where repository_id = :repository and source_id = :source")
                .param("repository", repositoryId)
                .param("source", sourceId)
                .update();
    }

    public void replaceWatches(UUID repositoryId, Set<String> sourceIds) {
        tx.executeWithoutResult(
                status -> {
                    List<String> existing = watches(repositoryId);
                    for (String source : existing) {
                        if (!sourceIds.contains(source)) {
                            unwatch(repositoryId, source);
                        }
                    }
                    for (String source : sourceIds) {
                        watch(repositoryId, source);
                    }
                });
    }

    public List<String> watches(UUID repositoryId) {
        return jdbc.sql(
                        "select source_id from repository_watch where repository_id = :repository order by source_id")
                .param("repository", repositoryId)
                .query(String.class)
                .list();
    }

    public List<Repository> watching(String sourceId) {
        return jdbc.sql(
                        "select "
                                + REPOSITORY_COLUMNS
                                + """
                                 from repository r
                                 join repository_watch w on w.repository_id = r.id
                                 join installation i on i.id = r.installation_id
                                 where w.source_id = :source and r.enabled and i.status = 'ACTIVE'
                                 order by r.full_name
                                """)
                .param("source", sourceId)
                .query(RepositoryStore::repository)
                .list();
    }

    private static Installation installation(ResultSet rs, int row) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        long github = rs.getLong("github_installation_id");
        return new Installation(
                rs.getObject("id", UUID.class),
                rs.wasNull() ? null : github,
                rs.getString("account_login"),
                rs.getString("status"),
                created.toInstant());
    }

    private static Repository repository(ResultSet rs, int row) throws SQLException {
        long github = rs.getLong("github_repo_id");
        boolean nullGithub = rs.wasNull();
        return new Repository(
                rs.getObject("id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                nullGithub ? null : github,
                rs.getString("full_name"),
                rs.getString("default_branch"),
                rs.getBoolean("enabled"),
                rs.getString("last_indexed_sha"));
    }
}
