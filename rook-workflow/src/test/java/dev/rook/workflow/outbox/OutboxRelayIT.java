package dev.rook.workflow.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.core.json.Json;
import dev.rook.db.Outbox;
import dev.rook.testkit.TestClock;
import dev.rook.testkit.TestDatabases;
import dev.rook.testkit.TestKafka;
import dev.rook.workflow.Backoff;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

class OutboxRelayIT {

    private static final RelayConfig CONFIG =
            new RelayConfig(
                    50,
                    4,
                    Duration.ofSeconds(10),
                    Duration.ofMillis(50),
                    new Backoff(Duration.ofSeconds(2), 2.0, Duration.ofSeconds(30)),
                    7_330_123L);

    private DataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private String topic;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dataSource = TestDatabases.fresh();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        topic = "relay-" + UUID.randomUUID().toString().substring(0, 8);
        TestKafka.createTopic(topic, 3);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private Producer<String, String> realProducer() {
        KafkaProducer<String, String> producer = new KafkaProducer<>(TestKafka.producerConfig());
        closeables.add(producer);
        return producer;
    }

    private OutboxRelay relay(Producer<String, String> producer, Clock clock, RelayConfig config) {
        OutboxRelay relay =
                new OutboxRelay(dataSource, jdbc, tx, producer, clock, config, RelayMetrics.NOOP);
        closeables.add(relay);
        return relay;
    }

    private long append(String key, String value) {
        return Outbox.append(
                jdbc,
                topic,
                key,
                Json.write(Map.of("v", value)),
                Map.of("messageId", key + ":" + value));
    }

    private static String valueOf(String payload) {
        return Json.read(payload, new TypeReference<Map<String, String>>() {}).get("v");
    }

    private List<ConsumerRecord<String, String>> consume(int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(TestKafka.consumerConfig("verify-" + UUID.randomUUID()))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(records::add);
            }
            consumer.poll(Duration.ofMillis(500)).forEach(records::add);
        }
        return records;
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void pendingMessagesAreSentToKafkaWithHeadersAndMarkedPublished() {
        append("source-1", "1");
        append("source-1", "2");
        OutboxRelay relay = relay(realProducer(), Clock.systemUTC(), CONFIG);
        assertThat(relay.pump()).isEqualTo(2);
        assertThat(count("select count(*) from outbox where published_at is null")).isZero();
        List<ConsumerRecord<String, String>> records = consume(2, Duration.ofSeconds(15));
        assertThat(records).extracting(r -> valueOf(r.value())).containsExactly("1", "2");
        ConsumerRecord<String, String> first = records.get(0);
        assertThat(
                        new String(
                                first.headers().lastHeader("messageId").value(),
                                StandardCharsets.UTF_8))
                .isEqualTo("source-1:1");
        assertThat(first.headers().lastHeader("outboxId")).isNotNull();
    }

    @Test
    void publishedMessagesAreNotSentAgain() {
        append("source-1", "a");
        OutboxRelay relay = relay(realProducer(), Clock.systemUTC(), CONFIG);
        assertThat(relay.pump()).isEqualTo(1);
        assertThat(relay.pump()).isZero();
        assertThat(consume(1, Duration.ofSeconds(15))).hasSize(1);
    }

    @Test
    void messagesOfOneKeyKeepTheirOrder() {
        int keys = 5;
        int perKey = 40;
        for (int i = 0; i < perKey; i++) {
            for (int k = 0; k < keys; k++) {
                append("key-" + k, Integer.toString(i));
            }
        }
        OutboxRelay relay = relay(realProducer(), Clock.systemUTC(), CONFIG);
        while (relay.pump() > 0) {
            continue;
        }
        List<ConsumerRecord<String, String>> records =
                consume(keys * perKey, Duration.ofSeconds(30));
        assertThat(records).hasSize(keys * perKey);
        Map<String, Integer> last = new HashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            int value = Integer.parseInt(valueOf(record.value()));
            Integer previous = last.put(record.key(), value);
            assertThat(previous == null || previous < value)
                    .as("order of " + record.key())
                    .isTrue();
        }
    }

    @Test
    void aFailedSendIsRetriedLaterAndBlocksOnlyItsOwnKey() {
        TestClock clock = TestClock.at("2026-08-20T10:00:00Z");
        FaultyProducer producer = new FaultyProducer();
        producer.failWhen(record -> record.key().equals("flaky"));
        append("flaky", "first");
        append("flaky", "second");
        append("steady", "only");
        OutboxRelay relay = relay(producer.producer(), clock, CONFIG);
        assertThat(relay.pump()).isEqualTo(1);
        assertThat(producer.sent()).extracting(r -> r.key()).containsExactly("steady");
        assertThat(
                        count(
                                "select attempts from outbox where message_key = 'flaky' order by id limit 1"))
                .isEqualTo(1);
        assertThat(relay.pump()).isZero();
        producer.healthy();
        assertThat(relay.pump()).isZero();
        clock.advance(Duration.ofMinutes(1));
        assertThat(relay.pump()).isEqualTo(2);
        assertThat(producer.sent())
                .extracting(r -> valueOf(r.value()))
                .containsExactly("only", "first", "second");
    }

    @Test
    void messagesThatKeepFailingAreParkedWithADeadLetter() {
        TestClock clock = TestClock.at("2026-08-20T10:00:00Z");
        FaultyProducer producer = new FaultyProducer();
        producer.failWhen(record -> true);
        append("doomed", "payload");
        OutboxRelay relay = relay(producer.producer(), clock, CONFIG.withMaxAttempts(3));
        for (int i = 0; i < 3; i++) {
            relay.pump();
            clock.advance(Duration.ofMinutes(5));
        }
        assertThat(count("select count(*) from dead_letter where source = 'outbox'")).isEqualTo(1);
        assertThat(jdbc.sql("select last_error from outbox").query(String.class).single())
                .startsWith("parked:");
        assertThat(count("select count(*) from outbox where published_at is null")).isZero();
        assertThat(relay.pump()).isZero();
    }

    @Test
    void aFailureAfterSendingCausesAnAtLeastOnceRedelivery() {
        jdbc.sql(
                        """
                        create function reject_mark() returns trigger as $$
                        begin
                          raise exception 'cannot mark published';
                        end;
                        $$ language plpgsql
                        """)
                .update();
        jdbc.sql(
                        """
                        create trigger reject_mark before update of published_at on outbox
                        for each row when (new.message_key = 'crash') execute function reject_mark()
                        """)
                .update();
        append("crash", "payload");
        OutboxRelay relay = relay(realProducer(), Clock.systemUTC(), CONFIG);
        org.assertj.core.api.Assertions.assertThatThrownBy(relay::pump)
                .isInstanceOf(RuntimeException.class);
        assertThat(count("select count(*) from outbox where published_at is null")).isEqualTo(1);
        jdbc.sql("drop trigger reject_mark on outbox").update();
        assertThat(relay.pump()).isEqualTo(1);
        List<ConsumerRecord<String, String>> records = consume(2, Duration.ofSeconds(20));
        assertThat(records).hasSize(2);
        assertThat(records)
                .extracting(
                        r ->
                                new String(
                                        r.headers().lastHeader("messageId").value(),
                                        StandardCharsets.UTF_8))
                .containsOnly("crash:payload");
    }

    @Test
    void concurrentPumpsNeverSendTheSameMessageTwice() throws Exception {
        int total = 200;
        for (int i = 0; i < total; i++) {
            append("key-" + (i % 20), Integer.toString(i));
        }
        FaultyProducer producer = new FaultyProducer();
        OutboxRelay relay =
                relay(producer.producer(), Clock.systemUTC(), CONFIG.withMaxAttempts(5));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    int sent = 0;
                                    int n;
                                    while ((n = relay.pump()) > 0) {
                                        sent += n;
                                    }
                                    return sent;
                                }));
            }
            start.countDown();
            int sum = 0;
            for (Future<Integer> future : futures) {
                sum += future.get();
            }
            assertThat(sum).isEqualTo(total);
        }
        assertThat(producer.sent()).hasSize(total);
        assertThat(count("select count(*) from outbox where published_at is null")).isZero();
    }

    @Test
    void onlyOneRelayLeadsAndAnotherTakesOverWhenItStops() throws Exception {
        FaultyProducer producerA = new FaultyProducer();
        FaultyProducer producerB = new FaultyProducer();
        AtomicInteger leaders = new AtomicInteger();
        RelayMetrics metrics =
                new RelayMetrics() {
                    @Override
                    public void published(String topic) {}

                    @Override
                    public void failed(String topic) {}

                    @Override
                    public void parked(String topic) {}

                    @Override
                    public void leader(boolean leading) {
                        leaders.addAndGet(leading ? 1 : -1);
                    }
                };
        OutboxRelay a =
                new OutboxRelay(
                        dataSource,
                        jdbc,
                        tx,
                        producerA.producer(),
                        Clock.systemUTC(),
                        CONFIG,
                        metrics);
        OutboxRelay b =
                new OutboxRelay(
                        dataSource,
                        jdbc,
                        tx,
                        producerB.producer(),
                        Clock.systemUTC(),
                        CONFIG,
                        metrics);
        closeables.add(a);
        closeables.add(b);
        a.start();
        b.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> leaders.get() == 1);
        for (int i = 0; i < 20; i++) {
            append("k" + i, "first-" + i);
        }
        await().atMost(Duration.ofSeconds(20))
                .until(() -> producerA.sent().size() + producerB.sent().size() == 20);
        boolean aLeads = producerA.sent().size() == 20;
        assertThat(aLeads ? producerB.sent() : producerA.sent()).isEmpty();
        if (aLeads) {
            a.stop();
        } else {
            b.stop();
        }
        for (int i = 0; i < 10; i++) {
            append("k" + i, "second-" + i);
        }
        await().atMost(Duration.ofSeconds(30))
                .until(() -> producerA.sent().size() + producerB.sent().size() == 30);
        assertThat(aLeads ? producerB.sent() : producerA.sent()).hasSize(10);
        assertThat(count("select count(*) from outbox where published_at is null")).isZero();
    }
}
