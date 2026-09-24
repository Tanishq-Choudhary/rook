package dev.rook.github.webhook;

import dev.rook.core.id.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class PullRequests {

    public enum State {
        OPEN,
        CLOSED,
        MERGED
    }

    public record Record(
            UUID id,
            UUID jobId,
            UUID repositoryId,
            int number,
            String url,
            String branch,
            String headSha,
            State state,
            Instant closedAt) {}

    private static final String COLUMNS =
            "id, job_id, repository_id, number, url, branch, head_sha, state, closed_at";

    private final JdbcClient jdbc;
    private final Clock clock;

    public PullRequests(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Record record(
            UUID jobId, UUID repositoryId, int number, String url, String branch, String headSha) {
        return jdbc.sql(
                        """
                        insert into pull_request (id, job_id, repository_id, number, url, branch, head_sha)
                        values (:id, :job, :repository, :number, :url, :branch, :sha)
                        on conflict (repository_id, branch) do update
                          set job_id = excluded.job_id, number = excluded.number, url = excluded.url,
                              head_sha = excluded.head_sha, state = 'OPEN', closed_at = null
                        returning """
                                + " "
                                + COLUMNS)
                .param("id", Ids.newId())
                .param("job", jobId)
                .param("repository", repositoryId)
                .param("number", number)
                .param("url", url)
                .param("branch", branch)
                .param("sha", headSha)
                .query((rs, row) -> read(rs))
                .single();
    }

    public Optional<Record> findByJob(UUID jobId) {
        return jdbc.sql("select " + COLUMNS + " from pull_request where job_id = :job")
                .param("job", jobId)
                .query((rs, row) -> read(rs))
                .optional();
    }

    public Optional<Record> findByBranch(UUID repositoryId, String branch) {
        return jdbc.sql(
                        "select "
                                + COLUMNS
                                + " from pull_request where repository_id = :repository and branch = :branch")
                .param("repository", repositoryId)
                .param("branch", branch)
                .query((rs, row) -> read(rs))
                .optional();
    }

    public boolean close(long githubRepoId, int number, boolean merged) {
        return jdbc.sql(
                                """
                                update pull_request p set state = :state, closed_at = :now
                                from repository r
                                where p.repository_id = r.id and r.github_repo_id = :github
                                  and p.number = :number and p.state = 'OPEN'
                                """)
                        .param("state", merged ? "MERGED" : "CLOSED")
                        .param("now", Timestamp.from(clock.instant()))
                        .param("github", githubRepoId)
                        .param("number", number)
                        .update()
                > 0;
    }

    private static Record read(ResultSet rs) throws SQLException {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new Record(
                rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getInt("number"),
                rs.getString("url"),
                rs.getString("branch"),
                rs.getString("head_sha"),
                State.valueOf(rs.getString("state")),
                closed == null ? null : closed.toInstant());
    }
}
