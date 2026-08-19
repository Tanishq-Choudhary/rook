package dev.rook.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.changes.store.ChangeIngestion;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.id.Ids;
import dev.rook.core.job.JobState;
import dev.rook.testkit.TestKafka;
import dev.rook.workflow.fanout.ChangeSetFanout;
import dev.rook.workflow.fanout.FanoutConfig;
import dev.rook.workflow.fanout.FanoutConsumer;
import dev.rook.workflow.fanout.FanoutMetrics;
import dev.rook.workflow.outbox.OutboxRelay;
import dev.rook.workflow.outbox.RelayConfig;
import dev.rook.workflow.outbox.RelayMetrics;
import dev.rook.workflow.repo.RepositoryStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class WorkflowPipelineIT {

    private static final RunnerConfig RUNNER =
            new RunnerConfig(
                    Duration.ofMillis(1500),
                    Duration.ofMillis(200),
                    Duration.ofSeconds(20),
                    new Backoff(Duration.ofMillis(100), 2.0, Duration.ofMillis(500)));

    private static final RelayConfig RELAY =
            RelayConfig.standard()
                    .withPollInterval(Duration.ofMillis(50))
                    .withBackoff(new Backoff(Duration.ofMillis(100), 2.0, Duration.ofMillis(500)));

    private WorkflowFixtures fixtures;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private RepositoryStore store;
    private ChangeIngestion ingestion;
    private String topic;
    private FanoutConfig fanoutConfig;
    private KafkaProducer<String, String> producer;
    private final List<AutoCloseable> running = new ArrayList<>();
    private WorkerPool pool;
    private int sequence;

    @BeforeEach
    void setUp() {
        fixtures = WorkflowFixtures.live();
        jdbc = fixtures.jdbc;
        tx = fixtures.tx;
        store = new RepositoryStore(jdbc, tx);
        ingestion = new ChangeIngestion(jdbc, tx, Clock.systemUTC());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        topic = "pipeline-" + suffix;
        TestKafka.createTopic(topic, 3);
        fanoutConfig =
                FanoutConfig.standard()
                        .withTopic(topic)
                        .withGroupId("pipeline-" + suffix)
                        .withRetryPause(Duration.ofMillis(200));
        producer = new KafkaProducer<>(TestKafka.producerConfig());
        jdbc.sql("create table effect (job_id uuid primary key, runs integer not null default 1)")
                .update();
        jdbc.sql("create sequence finish_order").update();
        jdbc.sql(
                        "alter table effect add column finished bigint not null default nextval('finish_order')")
                .update();
        fixtures.source("src-a");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (pool != null) {
            pool.close();
        }
        for (int i = running.size() - 1; i >= 0; i--) {
            running.get(i).close();
        }
        producer.close();
    }

    private UUID watcher(String source) {
        UUID repository = fixtures.repository();
        store.watch(repository, source);
        return repository;
    }

    private UUID ingest(String source, String from, String to) {
        ChangeSet set =
                new ChangeSet(
                        Ids.newId(),
                        source,
                        SourceKind.OPENAPI,
                        from,
                        to,
                        List.of(
                                ChangeEvent.of(
                                        source,
                                        from,
                                        to,
                                        ChangeKind.OPERATION_REMOVED,
                                        SymbolRef.operation("GET", "/v1/item" + (++sequence)),
                                        Map.of("operationId", "item" + sequence),
                                        "removed " + sequence)),
                        List.of(),
                        Instant.now().plusSeconds(sequence));
        UUID[] id = new UUID[1];
        retargeted(() -> id[0] = ingestion.ingest(set).changeSetId());
        return id[0];
    }

    private void retargeted(Runnable publish) {
        tx.executeWithoutResult(
                status -> {
                    publish.run();
                    jdbc.sql("update outbox set topic = :topic where published_at is null")
                            .param("topic", topic)
                            .update();
                });
    }

    private OutboxRelay startRelay(RelayMetrics metrics) {
        OutboxRelay relay =
                new OutboxRelay(
                        fixtures.dataSource,
                        jdbc,
                        tx,
                        new KafkaProducer<>(TestKafka.producerConfig()),
                        Clock.systemUTC(),
                        RELAY,
                        metrics);
        relay.start();
        running.add(relay);
        return relay;
    }

    private void startFanout() {
        ChangeSetFanout fanout = new ChangeSetFanout(jdbc, tx, store, fixtures.jobs, fanoutConfig);
        FanoutConsumer consumer =
                new FanoutConsumer(
                        TestKafka.bootstrapServers(), fanout, fanoutConfig, FanoutMetrics.NOOP);
        consumer.start();
        running.add(consumer);
    }

    private interface Step {
        StepResult run(JobContext context) throws Exception;
    }

    private static StepHandler handler(JobState state, Step step) {
        return new StepHandler() {
            @Override
            public JobState state() {
                return state;
            }

            @Override
            public StepResult execute(JobContext context) throws Exception {
                return step.run(context);
            }
        };
    }

    private StepResult recordEffect(JobContext context) {
        jdbc.sql(
                        "insert into effect (job_id) values (:id) on conflict (job_id) do update set runs = effect.runs + 1")
                .param("id", context.job().id())
                .update();
        return StepResult.advance(JobState.COMPLETED);
    }

    private void startWorkers(int size, Step verifying) {
        JobRunner runner =
                new JobRunner(
                        fixtures.jobs,
                        List.of(
                                handler(
                                        JobState.INDEXING,
                                        c -> StepResult.advance(JobState.PROPOSING)),
                                handler(
                                        JobState.PROPOSING,
                                        c -> StepResult.advance(JobState.VERIFYING)),
                                handler(JobState.VERIFYING, verifying),
                                handler(JobState.PUBLISHING, this::recordEffect)),
                        RUNNER,
                        WorkflowMetrics.NOOP,
                        RandomGenerator.of("L64X128MixRandom"));
        running.add(runner);
        pool = new WorkerPool(runner, "w", size, Duration.ofMillis(25));
        pool.start();
    }

    private void startWorkers(int size) {
        startWorkers(size, c -> StepResult.advance(JobState.PUBLISHING));
    }

    private void awaitCompleted(int expected, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(
                        () ->
                                assertThat(
                                                fixtures.count(
                                                        "select count(*) from migration_job where state = 'COMPLETED'"))
                                        .isEqualTo(expected));
    }

    private void assertNothingParked() {
        assertThat(fixtures.count("select count(*) from dead_letter")).isZero();
        assertThat(fixtures.count("select count(*) from effect where runs > 1")).isZero();
    }

    @Test
    void aChangeSetFlowsFromIngestionToCompletedJobsForEveryWatcher() {
        for (int i = 0; i < 3; i++) {
            watcher("src-a");
        }
        fixtures.source("src-b");
        watcher("src-b");
        UUID changeSet = ingest("src-a", "1.0.0", "2.0.0");
        startRelay(RelayMetrics.NOOP);
        startFanout();
        startWorkers(4);
        awaitCompleted(3, Duration.ofSeconds(60));
        assertThat(fixtures.count("select count(*) from effect")).isEqualTo(3);
        assertThat(fixtures.count("select count(*) from migration_job")).isEqualTo(3);
        assertThat(fixtures.count("select count(*) from outbox where published_at is null"))
                .isZero();
        for (UUID job :
                jdbc.sql("select id from migration_job where change_set_id = :c")
                        .param("c", changeSet)
                        .query(UUID.class)
                        .list()) {
            List<JobEvent> events = fixtures.jobs.events(job, 0, 100);
            assertThat(events.get(0).kind()).isEqualTo("JOB_CREATED");
            assertThat(events).extracting(JobEvent::id).isSorted();
            assertThat(events.stream().filter(e -> e.kind().equals("JOB_CREATED")).count())
                    .isEqualTo(1);
        }
        assertNothingParked();
    }

    @Test
    void duplicateDeliveriesAndReplaysDoNotRepeatAnyWork() throws Exception {
        for (int i = 0; i < 3; i++) {
            watcher("src-a");
        }
        UUID changeSet = ingest("src-a", "1.0.0", "2.0.0");
        startRelay(RelayMetrics.NOOP);
        startFanout();
        startWorkers(4);
        awaitCompleted(3, Duration.ofSeconds(60));
        String payload =
                jdbc.sql("select payload::text from outbox where message_key = 'src-a'")
                        .query(String.class)
                        .single();
        for (int i = 0; i < 4; i++) {
            producer.send(
                            new ProducerRecord<>(
                                    topic,
                                    null,
                                    "src-a",
                                    payload,
                                    List.of(
                                            new RecordHeader(
                                                    "messageId",
                                                    (changeSet + ":0")
                                                            .getBytes(StandardCharsets.UTF_8)))))
                    .get();
        }
        retargeted(
                () -> {
                    ingestion.replay(changeSet);
                    ingestion.replay(changeSet);
                });
        await().atMost(Duration.ofSeconds(30))
                .until(
                        () ->
                                fixtures.count(
                                                "select count(*) from processed_message where consumer = 'fanout'")
                                        == 3);
        await().atMost(Duration.ofSeconds(30))
                .until(
                        () ->
                                fixtures.count(
                                                "select count(*) from outbox where published_at is null")
                                        == 0);
        assertThat(fixtures.count("select count(*) from migration_job")).isEqualTo(3);
        assertThat(fixtures.count("select count(*) from effect")).isEqualTo(3);
        assertNothingParked();
    }

    @Test
    void workerCrashesInTheMiddleOfStepsLoseNoJobAndRepeatNoEffect() {
        for (int i = 0; i < 6; i++) {
            watcher("src-a");
        }
        ingest("src-a", "1.0.0", "2.0.0");
        ingest("src-a", "2.0.0", "3.0.0");
        startRelay(RelayMetrics.NOOP);
        startFanout();
        AtomicInteger seen = new AtomicInteger();
        ConcurrentMap<String, Boolean> crashed = new ConcurrentHashMap<>();
        startWorkers(
                6,
                c -> {
                    int n = seen.incrementAndGet();
                    if (n % 4 == 0 && crashed.putIfAbsent(c.workerId(), true) == null) {
                        pool.crash(c.workerId());
                        Thread.sleep(5_000);
                    }
                    return StepResult.advance(JobState.PUBLISHING);
                });
        awaitCompleted(12, Duration.ofSeconds(90));
        assertThat(crashed).isNotEmpty();
        assertThat(fixtures.count("select count(*) from effect")).isEqualTo(12);
        assertThat(fixtures.count("select count(*) from job_event where kind = 'LEASE_RECLAIMED'"))
                .isGreaterThanOrEqualTo(1);
        assertNothingParked();
    }

    @Test
    void aRelayFailoverBetweenBatchesLosesNoMessage() {
        for (int i = 0; i < 2; i++) {
            watcher("src-a");
        }
        AtomicBoolean firstLeads = new AtomicBoolean();
        AtomicBoolean secondLeads = new AtomicBoolean();
        OutboxRelay first = startRelay(leadership(firstLeads));
        OutboxRelay second = startRelay(leadership(secondLeads));
        startFanout();
        startWorkers(3);
        await().atMost(Duration.ofSeconds(15)).until(() -> firstLeads.get() ^ secondLeads.get());
        for (int i = 0; i < 4; i++) {
            ingest("src-a", i + ".0.0", (i + 1) + ".0.0");
        }
        awaitCompleted(8, Duration.ofSeconds(60));
        if (firstLeads.get()) {
            first.stop();
        } else {
            second.stop();
        }
        for (int i = 4; i < 8; i++) {
            ingest("src-a", i + ".0.0", (i + 1) + ".0.0");
        }
        awaitCompleted(16, Duration.ofSeconds(90));
        assertThat(fixtures.count("select count(*) from effect")).isEqualTo(16);
        assertNothingParked();
    }

    @Test
    void successiveChangeSetsOfOneRepositoryFinishInDetectionOrder() {
        UUID repository = watcher("src-a");
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            expected.add(ingest("src-a", i + ".0.0", (i + 1) + ".0.0"));
        }
        startRelay(RelayMetrics.NOOP);
        startFanout();
        startWorkers(4);
        awaitCompleted(6, Duration.ofSeconds(90));
        List<UUID> finished =
                jdbc.sql(
                                """
                                select j.change_set_id from effect e
                                join migration_job j on j.id = e.job_id
                                where j.repository_id = :repository
                                order by e.finished
                                """)
                        .param("repository", repository)
                        .query(UUID.class)
                        .list();
        assertThat(finished).containsExactlyElementsOf(expected);
        assertNothingParked();
    }

    private static RelayMetrics leadership(AtomicBoolean flag) {
        return new RelayMetrics() {
            @Override
            public void published(String topic) {}

            @Override
            public void failed(String topic) {}

            @Override
            public void parked(String topic) {}

            @Override
            public void leader(boolean leading) {
                flag.set(leading);
            }
        };
    }
}
