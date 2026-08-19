package dev.rook.workflow;

import dev.rook.core.error.RookException;
import dev.rook.core.id.Ids;
import dev.rook.core.job.JobState;
import dev.rook.core.json.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class JobRepository {

    static final String RUNNING = "('INDEXING','PROPOSING','VERIFYING','PUBLISHING')";
    private static final int CLAIM_RETRIES = 6;
    private static final String COLUMNS =
            "id, change_set_id, repository_id, state, attempt, max_attempts, priority, next_run_at,"
                    + " lease_owner, lease_expires_at, fence, last_error, checkpoint::text as checkpoint,"
                    + " created_at, updated_at, finished_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JobRepository(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public record CreateResult(boolean created, UUID jobId) {}

    public record Claim(Job job, boolean reclaimed, String previousOwner, String previousState) {}

    public CreateResult create(UUID changeSetId, UUID repositoryId, int priority, int maxAttempts) {
        return tx.execute(
                status -> {
                    UUID id = Ids.newId();
                    Instant now = clock.instant();
                    int inserted =
                            jdbc.sql(
                                            """
                                            insert into migration_job
                                              (id, change_set_id, repository_id, state, priority, max_attempts,
                                               next_run_at, created_at, updated_at)
                                            values (:id, :changeSet, :repository, 'PENDING', :priority, :max,
                                               :now, :now, :now)
                                            on conflict (change_set_id, repository_id) do nothing
                                            """)
                                    .param("id", id)
                                    .param("changeSet", changeSetId)
                                    .param("repository", repositoryId)
                                    .param("priority", priority)
                                    .param("max", maxAttempts)
                                    .param("now", Timestamp.from(now))
                                    .update();
                    if (inserted == 0) {
                        UUID existing =
                                jdbc.sql(
                                                "select id from migration_job where change_set_id = :cs and repository_id = :repo")
                                        .param("cs", changeSetId)
                                        .param("repo", repositoryId)
                                        .query(UUID.class)
                                        .single();
                        return new CreateResult(false, existing);
                    }
                    appendEvent(id, "JOB_CREATED", JobState.PENDING, Map.of());
                    return new CreateResult(true, id);
                });
    }

    public Optional<Job> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from migration_job where id = :id")
                .param("id", id)
                .query(JobRepository::map)
                .optional();
    }

    public Optional<Claim> claim(String owner, Duration lease) {
        for (int attempt = 0; attempt < CLAIM_RETRIES; attempt++) {
            try {
                return tx.execute(status -> claimOnce(owner, lease));
            } catch (DuplicateKeyException e) {
                continue;
            }
        }
        return Optional.empty();
    }

    private Optional<Claim> claimOnce(String owner, Duration lease) {
        Instant now = clock.instant();
        Optional<Claim> claim =
                jdbc.sql(
                                """
                                with candidate as (
                                  select c.id, c.lease_owner as previous_owner, c.state as previous_state
                                  from migration_job c
                                  where (
                                      c.state in """
                                        + RUNNING
                                        + """
                                      or ((c.state = 'PENDING'
                                           or (c.state = 'AWAITING_APPROVAL'
                                               and c.checkpoint ->> 'approval' = 'APPROVED'))
                                          and not exists (
                                            select 1 from migration_job r
                                            where r.repository_id = c.repository_id
                                              and r.state in """
                                        + RUNNING
                                        + """
                                          ))
                                    )
                                    and c.next_run_at <= :now
                                    and (c.lease_owner is null or c.lease_expires_at <= :now)
                                  order by c.priority desc, c.next_run_at, c.created_at
                                  limit 1
                                  for update skip locked
                                )
                                update migration_job j set
                                  state = case j.state
                                            when 'PENDING' then 'INDEXING'
                                            when 'AWAITING_APPROVAL' then 'PUBLISHING'
                                            else j.state end,
                                  lease_owner = :owner,
                                  lease_expires_at = :until,
                                  fence = j.fence + 1,
                                  attempt = case when candidate.previous_owner is not null
                                                 then j.attempt + 1 else j.attempt end,
                                  updated_at = :now
                                from candidate
                                where j.id = candidate.id
                                returning j.id, j.change_set_id, j.repository_id, j.state, j.attempt,
                                  j.max_attempts, j.priority, j.next_run_at, j.lease_owner, j.lease_expires_at,
                                  j.fence, j.last_error, j.checkpoint::text as checkpoint, j.created_at,
                                  j.updated_at, j.finished_at,
                                  candidate.previous_owner as previous_owner,
                                  candidate.previous_state as previous_state
                                """)
                        .param("now", Timestamp.from(now))
                        .param("until", Timestamp.from(now.plus(lease)))
                        .param("owner", owner)
                        .query(
                                (rs, n) ->
                                        new Claim(
                                                map(rs, n),
                                                rs.getString("previous_owner") != null,
                                                rs.getString("previous_owner"),
                                                rs.getString("previous_state")))
                        .optional();
        claim.ifPresent(
                c -> {
                    if (!c.previousState().equals(c.job().state().name())) {
                        appendEvent(
                                c.job().id(),
                                "STATE_CHANGED",
                                c.job().state(),
                                Map.of("from", c.previousState(), "to", c.job().state().name()));
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("owner", owner);
                    data.put("fence", c.job().fence());
                    data.put("attempt", c.job().attempt());
                    appendEvent(
                            c.job().id(),
                            c.reclaimed() ? "LEASE_RECLAIMED" : "LEASE_ACQUIRED",
                            c.job().state(),
                            c.reclaimed() ? withPrevious(data, c.previousOwner()) : data);
                });
        return claim;
    }

    private static Map<String, Object> withPrevious(Map<String, Object> data, String previous) {
        Map<String, Object> copy = new LinkedHashMap<>(data);
        copy.put("previousOwner", previous);
        return copy;
    }

    public boolean heartbeat(UUID id, long fence, Duration lease) {
        Instant now = clock.instant();
        return jdbc.sql(
                                """
                                update migration_job set lease_expires_at = :until, updated_at = :now
                                where id = :id and fence = :fence and lease_owner is not null
                                """)
                        .param("until", Timestamp.from(now.plus(lease)))
                        .param("now", Timestamp.from(now))
                        .param("id", id)
                        .param("fence", fence)
                        .update()
                > 0;
    }

    public boolean advance(
            UUID id,
            long fence,
            JobState from,
            JobState to,
            Map<String, ?> checkpointPatch,
            Map<String, ?> eventData) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalArgumentException("illegal transition " + from + " -> " + to);
        }
        Boolean applied =
                tx.execute(
                        status -> {
                            Instant now = clock.instant();
                            int updated =
                                    jdbc.sql(
                                                    """
                                                    update migration_job set
                                                      state = :to,
                                                      attempt = 0,
                                                      checkpoint = checkpoint || cast(:patch as jsonb),
                                                      lease_owner = null,
                                                      lease_expires_at = null,
                                                      next_run_at = :now,
                                                      last_error = null,
                                                      updated_at = :now,
                                                      finished_at = case when cast(:terminal as boolean) then cast(:now as timestamptz) else null end
                                                    where id = :id and fence = :fence and state = :from
                                                    """)
                                            .param("to", to.name())
                                            .param("patch", Json.write(checkpointPatch))
                                            .param("now", Timestamp.from(now))
                                            .param("terminal", to.isTerminal())
                                            .param("id", id)
                                            .param("fence", fence)
                                            .param("from", from.name())
                                            .update();
                            if (updated == 0) {
                                return false;
                            }
                            Map<String, Object> data = new LinkedHashMap<>(eventData);
                            data.put("from", from.name());
                            data.put("to", to.name());
                            appendEvent(id, "STATE_CHANGED", to, data);
                            return true;
                        });
        return Boolean.TRUE.equals(applied);
    }

    public int retry(UUID id, long fence, String error, Duration delay) {
        Integer attempt =
                tx.execute(
                        status -> {
                            Instant now = clock.instant();
                            Optional<Integer> updated =
                                    jdbc.sql(
                                                    """
                                                    update migration_job set
                                                      attempt = attempt + 1,
                                                      next_run_at = :next,
                                                      last_error = :error,
                                                      lease_owner = null,
                                                      lease_expires_at = null,
                                                      updated_at = :now
                                                    where id = :id and fence = :fence
                                                    returning attempt
                                                    """)
                                            .param("next", Timestamp.from(now.plus(delay)))
                                            .param("error", truncate(error))
                                            .param("now", Timestamp.from(now))
                                            .param("id", id)
                                            .param("fence", fence)
                                            .query(Integer.class)
                                            .optional();
                            if (updated.isEmpty()) {
                                return -1;
                            }
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("attempt", updated.get());
                            data.put("delayMillis", delay.toMillis());
                            data.put("error", truncate(error));
                            appendEvent(id, "RETRY_SCHEDULED", null, data);
                            return updated.get();
                        });
        return attempt == null ? -1 : attempt;
    }

    public boolean finish(UUID id, long fence, JobState from, JobState terminal, String reason) {
        if (!terminal.isTerminal() || !from.canTransitionTo(terminal)) {
            throw new IllegalArgumentException("illegal finish " + from + " -> " + terminal);
        }
        Boolean applied =
                tx.execute(
                        status -> {
                            Instant now = clock.instant();
                            int updated =
                                    jdbc.sql(
                                                    """
                                                    update migration_job set
                                                      state = :terminal,
                                                      last_error = :reason,
                                                      lease_owner = null,
                                                      lease_expires_at = null,
                                                      updated_at = :now,
                                                      finished_at = :now
                                                    where id = :id and fence = :fence and state = :from
                                                    """)
                                            .param("terminal", terminal.name())
                                            .param("reason", truncate(reason))
                                            .param("now", Timestamp.from(now))
                                            .param("id", id)
                                            .param("fence", fence)
                                            .param("from", from.name())
                                            .update();
                            if (updated == 0) {
                                return false;
                            }
                            if (terminal == JobState.DEAD) {
                                deadLetter(id, reason);
                            }
                            Map<String, Object> data = new LinkedHashMap<>();
                            data.put("from", from.name());
                            data.put("to", terminal.name());
                            data.put("reason", truncate(reason));
                            appendEvent(id, "STATE_CHANGED", terminal, data);
                            return true;
                        });
        return Boolean.TRUE.equals(applied);
    }

    public boolean release(UUID id, long fence) {
        return jdbc.sql(
                                """
                                update migration_job set lease_owner = null, lease_expires_at = null,
                                  next_run_at = :now, updated_at = :now
                                where id = :id and fence = :fence and lease_owner is not null
                                """)
                        .param("now", Timestamp.from(clock.instant()))
                        .param("id", id)
                        .param("fence", fence)
                        .update()
                > 0;
    }

    public boolean recordApproval(UUID id, boolean approved, String actor) {
        Boolean applied =
                tx.execute(
                        status -> {
                            Instant now = clock.instant();
                            if (approved) {
                                int updated =
                                        jdbc.sql(
                                                        """
                                                        update migration_job set
                                                          checkpoint = checkpoint || jsonb_build_object(
                                                              'approval', 'APPROVED', 'approvedBy', cast(:actor as text)),
                                                          next_run_at = :now, updated_at = :now
                                                        where id = :id and state = 'AWAITING_APPROVAL'
                                                          and lease_owner is null
                                                        """)
                                                .param("actor", actor)
                                                .param("now", Timestamp.from(now))
                                                .param("id", id)
                                                .update();
                                if (updated > 0) {
                                    appendEvent(
                                            id,
                                            "APPROVED",
                                            JobState.AWAITING_APPROVAL,
                                            Map.of("actor", actor));
                                }
                                return updated > 0;
                            }
                            int updated =
                                    jdbc.sql(
                                                    """
                                                    update migration_job set
                                                      state = 'REJECTED',
                                                      checkpoint = checkpoint || jsonb_build_object(
                                                          'approval', 'REJECTED', 'rejectedBy', cast(:actor as text)),
                                                      last_error = 'rejected by reviewer',
                                                      updated_at = :now, finished_at = :now
                                                    where id = :id and state = 'AWAITING_APPROVAL'
                                                      and lease_owner is null
                                                    """)
                                            .param("actor", actor)
                                            .param("now", Timestamp.from(now))
                                            .param("id", id)
                                            .update();
                            if (updated > 0) {
                                appendEvent(
                                        id,
                                        "STATE_CHANGED",
                                        JobState.REJECTED,
                                        Map.of(
                                                "actor",
                                                actor,
                                                "from",
                                                "AWAITING_APPROVAL",
                                                "to",
                                                "REJECTED"));
                            }
                            return updated > 0;
                        });
        return Boolean.TRUE.equals(applied);
    }

    public List<JobEvent> events(UUID jobId, long afterId, int limit) {
        return jdbc.sql(
                        """
                        select id, job_id, at, kind, state, data::text as data from job_event
                        where job_id = :job and id > :after order by id limit :limit
                        """)
                .param("job", jobId)
                .param("after", afterId)
                .param("limit", limit)
                .query(JobRepository::mapEvent)
                .list();
    }

    public Map<JobState, Long> countByState() {
        Map<JobState, Long> counts = new EnumMap<>(JobState.class);
        for (JobState state : JobState.values()) {
            counts.put(state, 0L);
        }
        jdbc.sql("select state, count(*) as total from migration_job group by state")
                .query(
                        (rs, n) -> {
                            counts.put(
                                    JobState.valueOf(rs.getString("state")), rs.getLong("total"));
                            return null;
                        })
                .list();
        return counts;
    }

    public void recordEvent(UUID jobId, String kind, JobState state, Map<String, ?> data) {
        appendEvent(jobId, kind, state, data);
    }

    void appendEvent(UUID jobId, String kind, JobState state, Map<String, ?> data) {
        jdbc.sql(
                        """
                        insert into job_event (job_id, at, kind, state, data)
                        values (:job, :at, :kind, :state, cast(:data as jsonb))
                        """)
                .param("job", jobId)
                .param("at", Timestamp.from(clock.instant()))
                .param("kind", kind)
                .param("state", state == null ? null : state.name())
                .param("data", Json.write(data))
                .update();
    }

    private void deadLetter(UUID jobId, String reason) {
        jdbc.sql(
                        """
                        insert into dead_letter (id, job_id, source, reason, payload, created_at)
                        select :id, j.id, 'job', :reason,
                               jsonb_build_object('state', j.state, 'attempt', j.attempt,
                                                  'error', j.last_error, 'checkpoint', j.checkpoint),
                               :now
                        from migration_job j where j.id = :job
                        """)
                .param("id", Ids.newId())
                .param("reason", truncate(reason))
                .param("now", Timestamp.from(clock.instant()))
                .param("job", jobId)
                .update();
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    private static Job map(ResultSet rs, int row) throws SQLException {
        Timestamp lease = rs.getTimestamp("lease_expires_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("change_set_id", UUID.class),
                rs.getObject("repository_id", UUID.class),
                JobState.valueOf(rs.getString("state")),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                rs.getInt("priority"),
                rs.getTimestamp("next_run_at").toInstant(),
                rs.getString("lease_owner"),
                lease == null ? null : lease.toInstant(),
                rs.getLong("fence"),
                rs.getString("last_error"),
                Json.tree(rs.getString("checkpoint")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }

    private static JobEvent mapEvent(ResultSet rs, int row) throws SQLException {
        return new JobEvent(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                rs.getTimestamp("at").toInstant(),
                rs.getString("kind"),
                rs.getString("state"),
                Json.tree(rs.getString("data")));
    }

    static RookException notFound(UUID id) {
        return RookException.notFound("job", id);
    }
}
