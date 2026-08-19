package dev.rook.workflow.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.core.json.Json;
import dev.rook.testkit.TestKafka;
import dev.rook.workflow.WorkflowFixtures;
import dev.rook.workflow.repo.RepositoryStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FanoutConsumerIT {

    private static final class CountingMetrics implements FanoutMetrics {

        final AtomicInteger handled = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();

        @Override
        public void handled(FanoutResult result) {
            handled.incrementAndGet();
        }

        @Override
        public void failed() {
            failed.incrementAndGet();
        }
    }

    private WorkflowFixtures fixtures;
    private RepositoryStore store;
    private FanoutConfig config;
    private ChangeSetFanout fanout;
    private KafkaProducer<String, String> producer;
    private final List<FanoutConsumer> consumers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixtures = WorkflowFixtures.live();
        store = new RepositoryStore(fixtures.jdbc, fixtures.tx);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        config =
                FanoutConfig.standard()
                        .withTopic("fanout-" + suffix)
                        .withGroupId("group-" + suffix)
                        .withRetryPause(Duration.ofMillis(200));
        TestKafka.createTopic(config.topic(), 3);
        fanout = new ChangeSetFanout(fixtures.jdbc, fixtures.tx, store, fixtures.jobs, config);
        producer = new KafkaProducer<>(TestKafka.producerConfig());
    }

    @AfterEach
    void tearDown() {
        consumers.forEach(FanoutConsumer::close);
        producer.close();
    }

    private FanoutConsumer consumer(FanoutMetrics metrics) {
        FanoutConsumer consumer =
                new FanoutConsumer(TestKafka.bootstrapServers(), fanout, config, metrics);
        consumers.add(consumer);
        return consumer;
    }

    private void publish(String key, String messageId, String body) throws Exception {
        List<RecordHeader> headers = new ArrayList<>();
        if (messageId != null) {
            headers.add(new RecordHeader("messageId", messageId.getBytes(StandardCharsets.UTF_8)));
        }
        producer.send(
                        new ProducerRecord<>(
                                config.topic(), null, key, body, new ArrayList<>(headers)))
                .get();
    }

    private String body(UUID changeSetId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changeSetId", changeSetId);
        body.put("sourceId", "src");
        body.put("requiresMigration", true);
        body.put("highestSeverity", "BREAKING");
        return Json.write(body);
    }

    private int jobs() {
        return fixtures.count("select count(*) from migration_job");
    }

    private UUID watcher() {
        UUID repository = fixtures.repository();
        store.watch(repository, "src");
        return repository;
    }

    @Test
    void messagesFromKafkaBecomeJobs() throws Exception {
        watcher();
        watcher();
        CountingMetrics metrics = new CountingMetrics();
        consumer(metrics).start();
        for (int i = 0; i < 4; i++) {
            UUID changeSet = fixtures.changeSet();
            publish("src", changeSet + ":0", body(changeSet));
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> jobs() == 8);
        assertThat(metrics.handled.get()).isEqualTo(4);
    }

    @Test
    void duplicateDeliveriesCreateOneJobPerRepository() throws Exception {
        watcher();
        CountingMetrics metrics = new CountingMetrics();
        consumer(metrics).start();
        UUID changeSet = fixtures.changeSet();
        for (int i = 0; i < 5; i++) {
            publish("src", changeSet + ":0", body(changeSet));
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> metrics.handled.get() == 5);
        assertThat(jobs()).isEqualTo(1);
        assertThat(fixtures.count("select count(*) from processed_message")).isEqualTo(1);
    }

    @Test
    void aBadMessageIsParkedAndDoesNotBlockTheOnesAfterIt() throws Exception {
        watcher();
        CountingMetrics metrics = new CountingMetrics();
        consumer(metrics).start();
        publish("src", "bad:0", "this is not json");
        UUID changeSet = fixtures.changeSet();
        publish("src", changeSet + ":0", body(changeSet));
        await().atMost(Duration.ofSeconds(30)).until(() -> jobs() == 1);
        assertThat(fixtures.count("select count(*) from dead_letter where source = 'fanout'"))
                .isEqualTo(1);
        assertThat(metrics.failed.get()).isZero();
    }

    @Test
    void aMessageWithoutAnIdIsParked() throws Exception {
        watcher();
        consumer(FanoutMetrics.NOOP).start();
        UUID changeSet = fixtures.changeSet();
        publish("src", null, body(changeSet));
        await().atMost(Duration.ofSeconds(30))
                .until(
                        () ->
                                fixtures.count(
                                                "select count(*) from dead_letter where source = 'fanout'")
                                        == 1);
        assertThat(jobs()).isZero();
    }

    @Test
    void aDatabaseFailureIsRetriedUntilTheMessageGoesThrough() throws Exception {
        watcher();
        fixtures.jdbc
                .sql(
                        """
                        create function refuse_job() returns trigger as $$
                        begin
                          raise exception 'database unavailable';
                        end;
                        $$ language plpgsql
                        """)
                .update();
        fixtures.jdbc
                .sql(
                        "create trigger refuse_job before insert on migration_job for each row execute function refuse_job()")
                .update();
        CountingMetrics metrics = new CountingMetrics();
        consumer(metrics).start();
        UUID changeSet = fixtures.changeSet();
        publish("src", changeSet + ":0", body(changeSet));
        await().atMost(Duration.ofSeconds(30)).until(() -> metrics.failed.get() >= 2);
        assertThat(jobs()).isZero();
        fixtures.jdbc.sql("drop trigger refuse_job on migration_job").update();
        await().atMost(Duration.ofSeconds(30)).until(() -> jobs() == 1);
        assertThat(fixtures.count("select count(*) from processed_message")).isEqualTo(1);
    }

    @Test
    void aRestartedConsumerResumesAfterTheCommittedOffset() throws Exception {
        watcher();
        CountingMetrics first = new CountingMetrics();
        FanoutConsumer one = consumer(first);
        one.start();
        for (int i = 0; i < 3; i++) {
            UUID changeSet = fixtures.changeSet();
            publish("src", changeSet + ":0", body(changeSet));
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> jobs() == 3);
        await().atMost(Duration.ofSeconds(10)).until(() -> first.handled.get() == 3);
        one.stop();
        for (int i = 0; i < 2; i++) {
            UUID changeSet = fixtures.changeSet();
            publish("src", changeSet + ":0", body(changeSet));
        }
        CountingMetrics second = new CountingMetrics();
        consumer(second).start();
        await().atMost(Duration.ofSeconds(30)).until(() -> jobs() == 5);
        await().atMost(Duration.ofSeconds(10)).until(() -> second.handled.get() >= 2);
        assertThat(second.handled.get()).isEqualTo(2);
    }

    @Test
    void stoppingTwiceAndStartingTwiceIsHarmless() {
        FanoutConsumer consumer = consumer(FanoutMetrics.NOOP);
        consumer.start();
        consumer.start();
        consumer.stop();
        consumer.stop();
    }
}
