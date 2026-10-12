package dev.rook.workflow.outbox;

import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

public class OutboxRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Producer<String, String> producer;
    private final Clock clock;
    private final RelayConfig config;
    private final RelayMetrics metrics;
    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
    private volatile boolean running;
    private Thread loop;

    public OutboxRelay(
            DataSource dataSource,
            JdbcClient jdbc,
            TransactionTemplate tx,
            Producer<String, String> producer,
            Clock clock,
            RelayConfig config,
            RelayMetrics metrics) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.tx = tx;
        this.producer = producer;
        this.clock = clock;
        this.config = config;
        this.metrics = metrics;
    }

    private record Pending(
            long id, String topic, String key, String payload, String headers, int attempts) {}

    public int pump() {
        Integer sent = tx.execute(status -> pumpInTransaction());
        return sent == null ? 0 : sent;
    }

    private int pumpInTransaction() {
        Instant now = clock.instant();
        List<Pending> batch =
                jdbc.sql(
                                """
                                select x.id, x.topic, x.message_key, x.payload::text as payload,
                                       x.headers::text as headers, x.attempts
                                from outbox x
                                where x.published_at is null
                                  and x.next_attempt_at <= :now
                                  and not exists (
                                    select 1 from outbox o
                                    where o.topic = x.topic and o.message_key = x.message_key
                                      and o.published_at is null and o.id < x.id
                                      and o.next_attempt_at > :now)
                                order by x.id
                                limit :limit
                                for update skip locked
                                """)
                        .param("now", Timestamp.from(now))
                        .param("limit", config.batchSize())
                        .query(
                                (rs, n) ->
                                        new Pending(
                                                rs.getLong("id"),
                                                rs.getString("topic"),
                                                rs.getString("message_key"),
                                                rs.getString("payload"),
                                                rs.getString("headers"),
                                                rs.getInt("attempts")))
                        .list();
        Set<String> blocked = new HashSet<>();
        int published = 0;
        for (Pending pending : batch) {
            String stream = pending.topic() + "|" + pending.key();
            if (blocked.contains(stream)) {
                continue;
            }
            try {
                send(pending);
                jdbc.sql("update outbox set published_at = :now where id = :id")
                        .param("now", Timestamp.from(now))
                        .param("id", pending.id())
                        .update();
                metrics.published(pending.topic());
                published++;
            } catch (SendFailure failure) {
                blocked.add(stream);
                handleFailure(pending, failure, now);
            }
        }
        return published;
    }

    private void send(Pending pending) throws SendFailure {
        List<RecordHeader> headers = new ArrayList<>();
        Map<String, String> custom =
                Json.read(pending.headers(), new TypeReference<Map<String, String>>() {});
        custom.forEach(
                (k, v) -> headers.add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));
        headers.add(
                new RecordHeader(
                        "outboxId", Long.toString(pending.id()).getBytes(StandardCharsets.UTF_8)));
        ProducerRecord<String, String> record =
                new ProducerRecord<>(
                        pending.topic(),
                        null,
                        pending.key(),
                        pending.payload(),
                        new ArrayList<>(headers));
        try {
            producer.send(record).get(config.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("relay interrupted while sending", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            Throwable cause =
                    e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new SendFailure(
                    cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }
    }

    private void handleFailure(Pending pending, SendFailure failure, Instant now) {
        int attempts = pending.attempts() + 1;
        String reason = failure.getMessage();
        if (attempts >= config.maxAttempts()) {
            jdbc.sql(
                            """
                            insert into dead_letter (id, job_id, source, reason, payload, created_at)
                            values (:id, null, 'outbox', :reason,
                                    jsonb_build_object('topic', cast(:topic as text), 'key', cast(:key as text),
                                                       'outboxId', cast(:outbox as bigint),
                                                       'payload', cast(:payload as jsonb)),
                                    :now)
                            """)
                    .param("id", Ids.newId())
                    .param("reason", reason)
                    .param("topic", pending.topic())
                    .param("key", pending.key())
                    .param("outbox", pending.id())
                    .param("payload", pending.payload())
                    .param("now", Timestamp.from(now))
                    .update();
            jdbc.sql(
                            "update outbox set attempts = :attempts, last_error = :error, published_at = :now where id = :id")
                    .param("attempts", attempts)
                    .param("error", "parked: " + reason)
                    .param("now", Timestamp.from(now))
                    .param("id", pending.id())
                    .update();
            metrics.parked(pending.topic());
            log.error(
                    "outbox message {} parked after {} attempts: {}",
                    pending.id(),
                    attempts,
                    reason);
            return;
        }
        Duration delay = config.backoff().delay(attempts, random);
        jdbc.sql(
                        "update outbox set attempts = :attempts, last_error = :error, next_attempt_at = :next where id = :id")
                .param("attempts", attempts)
                .param("error", reason)
                .param("next", Timestamp.from(now.plus(delay)))
                .param("id", pending.id())
                .update();
        metrics.failed(pending.topic());
        log.warn(
                "outbox message {} not published (attempt {}): {}", pending.id(), attempts, reason);
    }

    public boolean running() {
        return running;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loop = Thread.ofPlatform().daemon().name("outbox-relay").start(this::lead);
    }

    private void lead() {
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                if (!tryLock(connection)) {
                    pause(config.pollInterval().multipliedBy(4));
                    continue;
                }
                metrics.leader(true);
                try {
                    while (running && stillConnected(connection)) {
                        int sent = pump();
                        if (sent == 0) {
                            pause(config.pollInterval());
                        }
                    }
                } finally {
                    unlock(connection);
                    metrics.leader(false);
                }
            } catch (SQLException | RuntimeException e) {
                log.error("outbox relay iteration failed", e);
                pause(Duration.ofSeconds(1));
            }
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, config.leaderLockKey());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) {
        try (PreparedStatement statement =
                connection.prepareStatement("select pg_advisory_unlock(?)")) {
            statement.setLong(1, config.leaderLockKey());
            statement.execute();
        } catch (SQLException e) {
            log.debug("could not release the relay lock: {}", e.getMessage());
        }
    }

    private boolean stillConnected(Connection connection) {
        try {
            return !connection.isClosed() && connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
        if (loop != null) {
            loop.interrupt();
            try {
                loop.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            loop = null;
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static final class SendFailure extends Exception {

        SendFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
