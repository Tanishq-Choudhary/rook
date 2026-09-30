package dev.rook.server.api;

import dev.rook.core.json.Json;
import dev.rook.server.api.Dtos.ChangeSetSummary;
import dev.rook.server.api.Dtos.EventDto;
import dev.rook.server.api.Dtos.InstallationDto;
import dev.rook.server.api.Dtos.JobSummary;
import dev.rook.server.api.Dtos.Page;
import dev.rook.server.api.Dtos.RepositoryDto;
import dev.rook.server.api.Dtos.SourceDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ApiQueries {

    private static final String JOB_COLUMNS =
            "j.id, j.change_set_id, j.repository_id, r.full_name, j.state, j.attempt, j.priority,"
                    + " j.last_error, j.created_at, j.updated_at, j.finished_at";
    private static final String JOB_FROM =
            " from migration_job j join repository r on r.id = j.repository_id";

    private final JdbcClient jdbc;

    public ApiQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<InstallationDto> installations(UUID filter) {
        return jdbc.sql(
                        """
                        select id, github_installation_id, account_login, status, created_at
                        from installation where (cast(:filter as uuid) is null or id = :filter)
                        order by account_login
                        """)
                .param("filter", filter)
                .query(ApiQueries::installation)
                .list();
    }

    public Optional<InstallationDto> installation(UUID id) {
        return jdbc.sql(
                        "select id, github_installation_id, account_login, status, created_at"
                                + " from installation where id = :id")
                .param("id", id)
                .query(ApiQueries::installation)
                .optional();
    }

    public Page<RepositoryDto> repositories(UUID installation, int page, int size) {
        long total =
                jdbc.sql(
                                "select count(*) from repository"
                                        + " where (cast(:i as uuid) is null or installation_id = :i)")
                        .param("i", installation)
                        .query(Long.class)
                        .single();
        List<RepositoryDto> items =
                jdbc.sql(
                                """
                                select id, installation_id, github_repo_id, full_name, default_branch, enabled,
                                       last_indexed_sha
                                from repository where (cast(:i as uuid) is null or installation_id = :i)
                                order by full_name limit :limit offset :offset
                                """)
                        .param("i", installation)
                        .param("limit", size)
                        .param("offset", (long) page * size)
                        .query((rs, row) -> repository(rs, List.of()))
                        .list();
        List<RepositoryDto> withWatches = new ArrayList<>();
        for (RepositoryDto item : items) {
            withWatches.add(withWatches(item));
        }
        return new Page<>(withWatches, page, size, total);
    }

    public Optional<RepositoryDto> repository(UUID id) {
        return jdbc.sql(
                        """
                        select id, installation_id, github_repo_id, full_name, default_branch, enabled,
                               last_indexed_sha from repository where id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> repository(rs, List.of()))
                .optional()
                .map(this::withWatches);
    }

    public List<SourceDto> sources() {
        return jdbc.sql(
                        """
                        select id, kind, display_name, location, last_version, last_checked_at, last_error,
                               enabled from watched_source order by id
                        """)
                .query(
                        (rs, row) ->
                                new SourceDto(
                                        rs.getString("id"),
                                        rs.getString("kind"),
                                        rs.getString("display_name"),
                                        rs.getString("location"),
                                        rs.getString("last_version"),
                                        instant(rs, "last_checked_at"),
                                        rs.getString("last_error"),
                                        rs.getBoolean("enabled")))
                .list();
    }

    public Page<ChangeSetSummary> changeSets(String source, int page, int size) {
        long total =
                jdbc.sql(
                                "select count(*) from change_set"
                                        + " where (cast(:s as text) is null or source_id = :s)")
                        .param("s", source)
                        .query(Long.class)
                        .single();
        List<ChangeSetSummary> items =
                jdbc.sql(
                                """
                                select c.id, c.source_id, c.from_version, c.to_version, c.highest_severity,
                                       c.requires_migration, c.detected_at,
                                       (select count(*) from change_event e where e.change_set_id = c.id) as events
                                from change_set c where (cast(:s as text) is null or c.source_id = :s)
                                order by c.detected_at desc, c.id limit :limit offset :offset
                                """)
                        .param("s", source)
                        .param("limit", size)
                        .param("offset", (long) page * size)
                        .query((rs, row) -> changeSet(rs))
                        .list();
        return new Page<>(items, page, size, total);
    }

    public Optional<ChangeSetSummary> changeSet(UUID id) {
        return jdbc.sql(
                        """
                        select c.id, c.source_id, c.from_version, c.to_version, c.highest_severity,
                               c.requires_migration, c.detected_at,
                               (select count(*) from change_event e where e.change_set_id = c.id) as events
                        from change_set c where c.id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> changeSet(rs))
                .optional();
    }

    public List<EventDto> events(UUID changeSetId) {
        return jdbc.sql(
                        """
                        select fingerprint, kind, severity, subject_kind, subject_name,
                               attributes::text as attributes, summary
                        from change_event where change_set_id = :id order by severity desc, kind, subject_name
                        """)
                .param("id", changeSetId)
                .query(
                        (rs, row) ->
                                new EventDto(
                                        rs.getString("fingerprint"),
                                        rs.getString("kind"),
                                        rs.getString("severity"),
                                        rs.getString("subject_kind"),
                                        rs.getString("subject_name"),
                                        Json.tree(rs.getString("attributes")),
                                        rs.getString("summary")))
                .list();
    }

    public String notes(UUID changeSetId) {
        return jdbc.sql("select notes::text from change_set where id = :id")
                .param("id", changeSetId)
                .query(String.class)
                .optional()
                .orElse("[]");
    }

    public Page<JobSummary> jobs(
            UUID installation, String state, UUID repository, int page, int size) {
        String where =
                " where (cast(:i as uuid) is null or r.installation_id = :i)"
                        + " and (cast(:state as text) is null or j.state = :state)"
                        + " and (cast(:repo as uuid) is null or j.repository_id = :repo)";
        long total =
                jdbc.sql("select count(*)" + JOB_FROM + where)
                        .param("i", installation)
                        .param("state", state)
                        .param("repo", repository)
                        .query(Long.class)
                        .single();
        List<JobSummary> items =
                jdbc.sql(
                                "select "
                                        + JOB_COLUMNS
                                        + JOB_FROM
                                        + where
                                        + " order by j.created_at desc, j.id limit :limit offset :offset")
                        .param("i", installation)
                        .param("state", state)
                        .param("repo", repository)
                        .param("limit", size)
                        .param("offset", (long) page * size)
                        .query((rs, row) -> job(rs))
                        .list();
        return new Page<>(items, page, size, total);
    }

    public Optional<JobSummary> job(UUID id) {
        return jdbc.sql("select " + JOB_COLUMNS + JOB_FROM + " where j.id = :id")
                .param("id", id)
                .query((rs, row) -> job(rs))
                .optional();
    }

    public Optional<UUID> jobInstallation(UUID jobId) {
        return jdbc.sql(
                        "select r.installation_id from migration_job j"
                                + " join repository r on r.id = j.repository_id where j.id = :id")
                .param("id", jobId)
                .query(UUID.class)
                .optional();
    }

    private RepositoryDto withWatches(RepositoryDto repository) {
        List<String> watches =
                jdbc.sql(
                                "select source_id from repository_watch where repository_id = :id"
                                        + " order by source_id")
                        .param("id", repository.id())
                        .query(String.class)
                        .list();
        return new RepositoryDto(
                repository.id(),
                repository.installationId(),
                repository.githubRepoId(),
                repository.fullName(),
                repository.defaultBranch(),
                repository.enabled(),
                repository.lastIndexedSha(),
                watches);
    }

    private static InstallationDto installation(ResultSet rs, int row) throws SQLException {
        return new InstallationDto(
                rs.getObject("id", UUID.class),
                (Long) rs.getObject("github_installation_id"),
                rs.getString("account_login"),
                rs.getString("status"),
                instant(rs, "created_at"));
    }

    private static RepositoryDto repository(ResultSet rs, List<String> watches)
            throws SQLException {
        return new RepositoryDto(
                rs.getObject("id", UUID.class),
                rs.getObject("installation_id", UUID.class),
                (Long) rs.getObject("github_repo_id"),
                rs.getString("full_name"),
                rs.getString("default_branch"),
                rs.getBoolean("enabled"),
                rs.getString("last_indexed_sha"),
                watches);
    }

    private static ChangeSetSummary changeSet(ResultSet rs) throws SQLException {
        return new ChangeSetSummary(
                rs.getObject("id", UUID.class),
                rs.getString("source_id"),
                rs.getString("from_version"),
                rs.getString("to_version"),
                rs.getString("highest_severity"),
                rs.getBoolean("requires_migration"),
                instant(rs, "detected_at"),
                rs.getInt("events"));
    }

    private static JobSummary job(ResultSet rs) throws SQLException {
        return new JobSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("change_set_id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                rs.getString("full_name"),
                rs.getString("state"),
                rs.getInt("attempt"),
                rs.getInt("priority"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
