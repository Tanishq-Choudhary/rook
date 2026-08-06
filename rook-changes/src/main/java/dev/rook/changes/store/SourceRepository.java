package dev.rook.changes.store;

import dev.rook.changes.source.WatchedSource;
import dev.rook.core.change.SourceKind;
import dev.rook.core.json.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public class SourceRepository {

    private static final String COLUMNS =
            "id, kind, display_name, location, config::text as config, etag, last_version,"
                    + " last_checked_at, last_error, poll_interval_seconds, enabled";

    private final JdbcClient jdbc;

    public SourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void register(WatchedSource source) {
        jdbc.sql(
                        """
                        insert into watched_source
                          (id, kind, display_name, location, config, poll_interval_seconds, enabled)
                        values (:id, :kind, :name, :location, cast(:config as jsonb), :interval, :enabled)
                        on conflict (id) do update set
                          display_name = excluded.display_name,
                          location = excluded.location,
                          config = excluded.config,
                          poll_interval_seconds = excluded.poll_interval_seconds,
                          enabled = excluded.enabled
                        """)
                .param("id", source.id())
                .param("kind", source.kind().name())
                .param("name", source.displayName())
                .param("location", source.location())
                .param("config", Json.write(source.config()))
                .param("interval", source.pollIntervalSeconds())
                .param("enabled", source.enabled())
                .update();
    }

    public Optional<WatchedSource> find(String id) {
        return jdbc.sql("select " + COLUMNS + " from watched_source where id = :id")
                .param("id", id)
                .query(SourceRepository::map)
                .optional();
    }

    public List<WatchedSource> list() {
        return jdbc.sql("select " + COLUMNS + " from watched_source order by id")
                .query(SourceRepository::map)
                .list();
    }

    public List<WatchedSource> claimDue(Instant now, int limit) {
        return jdbc.sql(
                        """
                        update watched_source s set last_checked_at = :now
                        where s.id in (
                          select id from watched_source
                          where enabled
                            and (last_checked_at is null
                                 or last_checked_at + make_interval(secs => poll_interval_seconds) <= :now)
                          order by last_checked_at asc nulls first
                          limit :limit
                          for update skip locked)
                        returning s.id, s.kind, s.display_name, s.location, s.config::text as config,
                          s.etag, s.last_version, s.last_checked_at, s.last_error,
                          s.poll_interval_seconds, s.enabled
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(SourceRepository::map)
                .list();
    }

    public void setEnabled(String id, boolean enabled) {
        jdbc.sql("update watched_source set enabled = :enabled where id = :id")
                .param("enabled", enabled)
                .param("id", id)
                .update();
    }

    private static WatchedSource map(ResultSet rs, int row) throws SQLException {
        Timestamp checked = rs.getTimestamp("last_checked_at");
        return new WatchedSource(
                rs.getString("id"),
                SourceKind.valueOf(rs.getString("kind")),
                rs.getString("display_name"),
                rs.getString("location"),
                Json.tree(rs.getString("config")),
                rs.getString("etag"),
                rs.getString("last_version"),
                checked == null ? null : checked.toInstant(),
                rs.getString("last_error"),
                rs.getInt("poll_interval_seconds"),
                rs.getBoolean("enabled"));
    }
}
