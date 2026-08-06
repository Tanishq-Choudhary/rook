package dev.rook.changes.source;

import dev.rook.core.error.RookException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcSnapshotStore implements SnapshotStore {

    private static final int MAX_INFLATED_BYTES = 128 * 1024 * 1024;

    private final JdbcClient jdbc;

    public JdbcSnapshotStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Snapshot> byHash(String sourceId, String contentHash) {
        return jdbc.sql(
                        """
                        select id, source_id, version, content_hash, content_gz, fetched_at
                        from source_snapshot where source_id = :source and content_hash = :hash
                        """)
                .param("source", sourceId)
                .param("hash", contentHash)
                .query(JdbcSnapshotStore::map)
                .optional();
    }

    @Override
    public Optional<Snapshot> byVersion(String sourceId, String version) {
        return jdbc.sql(
                        """
                        select id, source_id, version, content_hash, content_gz, fetched_at
                        from source_snapshot where source_id = :source and version = :version
                        """)
                .param("source", sourceId)
                .param("version", version)
                .query(JdbcSnapshotStore::map)
                .optional();
    }

    @Override
    public boolean save(Snapshot snapshot) {
        return jdbc.sql(
                                """
                                insert into source_snapshot
                                  (id, source_id, version, content_hash, content_gz, fetched_at)
                                values (:id, :source, :version, :hash, :content, :at)
                                on conflict do nothing
                                """)
                        .param("id", snapshot.id())
                        .param("source", snapshot.sourceId())
                        .param("version", snapshot.version())
                        .param("hash", snapshot.contentHash())
                        .param("content", deflate(snapshot.content()))
                        .param("at", Timestamp.from(snapshot.fetchedAt()))
                        .update()
                > 0;
    }

    private static Snapshot map(ResultSet rs, int row) throws SQLException {
        return new Snapshot(
                rs.getObject("id", UUID.class),
                rs.getString("source_id"),
                rs.getString("version"),
                rs.getString("content_hash"),
                inflate(rs.getBytes("content_gz")),
                rs.getTimestamp("fetched_at").toInstant());
    }

    static byte[] deflate(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    static byte[] inflate(byte[] compressed) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] bytes = in.readNBytes(MAX_INFLATED_BYTES + 1);
            if (bytes.length > MAX_INFLATED_BYTES) {
                throw RookException.invalid("stored snapshot is too large");
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
