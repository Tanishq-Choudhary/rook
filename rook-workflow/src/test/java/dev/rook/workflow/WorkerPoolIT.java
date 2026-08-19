package dev.rook.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rook.core.job.JobState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerPoolIT {

    private static final RunnerConfig CONFIG =
            new RunnerConfig(
                    Duration.ofMillis(1500),
                    Duration.ofMillis(200),
                    Duration.ofSeconds(20),
                    new Backoff(Duration.ofMillis(100), 2.0, Duration.ofMillis(500)));

    interface Step {
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

    private WorkflowFixtures fixtures;
    private JobRepository jobs;
    private JobRunner runner;
    private WorkerPool pool;

    @BeforeEach
    void setUp() {
        fixtures = WorkflowFixtures.live();
        jobs = fixtures.jobs;
        fixtures.jdbc
                .sql(
                        "create table effect (job_id uuid primary key, runs integer not null default 1)")
                .update();
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        if (runner != null) {
            runner.close();
        }
    }

    private void start(int workers, List<StepHandler> handlers) {
        runner =
                new JobRunner(
                        jobs,
                        handlers,
                        CONFIG,
                        WorkflowMetrics.NOOP,
                        RandomGenerator.of("L64X128MixRandom"));
        pool = new WorkerPool(runner, "w", workers, Duration.ofMillis(25));
        pool.start();
    }

    private void awaitAllInState(JobState state, int expected, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(
                        () ->
                                assertThat(
                                                fixtures.count(
                                                        "select count(*) from migration_job where state = '"
                                                                + state
                                                                + "'"))
                                        .isEqualTo(expected));
    }

    private List<StepHandler> pipeline(Step publish) {
        return List.of(
                handler(JobState.INDEXING, c -> StepResult.advance(JobState.PROPOSING)),
                handler(JobState.PROPOSING, c -> StepResult.advance(JobState.VERIFYING)),
                handler(JobState.VERIFYING, c -> StepResult.advance(JobState.PUBLISHING)),
                handler(JobState.PUBLISHING, c -> publish.run(c)));
    }

    private StepResult recordEffect(JobContext context) {
        fixtures.jdbc
                .sql(
                        "insert into effect (job_id) values (:id) on conflict (job_id) do update set runs = effect.runs + 1")
                .param("id", context.job().id())
                .update();
        return StepResult.advance(JobState.COMPLETED);
    }

    @Test
    void aPoolDrainsManyJobsAcrossRepositories() {
        int count = 80;
        for (int i = 0; i < count; i++) {
            fixtures.job(fixtures.repository());
        }
        start(8, pipeline(this::recordEffect));
        awaitAllInState(JobState.COMPLETED, count, Duration.ofSeconds(60));
        assertThat(fixtures.count("select count(*) from effect")).isEqualTo(count);
        assertThat(fixtures.count("select count(*) from effect where runs > 1")).isZero();
    }

    @Test
    void jobsOfOneRepositoryNeverRunConcurrently() {
        UUID repository = fixtures.repository();
        int count = 12;
        for (int i = 0; i < count; i++) {
            fixtures.job(repository);
        }
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<StepHandler> handlers =
                List.of(
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    int now = concurrent.incrementAndGet();
                                    peak.accumulateAndGet(now, Math::max);
                                    Thread.sleep(40);
                                    concurrent.decrementAndGet();
                                    return StepResult.advance(JobState.PROPOSING);
                                }),
                        handler(JobState.PROPOSING, c -> StepResult.advance(JobState.VERIFYING)),
                        handler(JobState.VERIFYING, c -> StepResult.advance(JobState.PUBLISHING)),
                        handler(JobState.PUBLISHING, c -> StepResult.advance(JobState.COMPLETED)));
        start(6, handlers);
        awaitAllInState(JobState.COMPLETED, count, Duration.ofSeconds(60));
        assertThat(peak.get()).isEqualTo(1);
    }

    @Test
    void aCrashedWorkersJobIsRecoveredByAnotherAndTheEffectStaysIdempotent() {
        int count = 12;
        for (int i = 0; i < count; i++) {
            fixtures.job(fixtures.repository());
        }
        AtomicReference<String> victim = new AtomicReference<>();
        AtomicInteger crashes = new AtomicInteger();
        start(
                3,
                pipeline(
                        c -> {
                            if (crashes.get() == 0 && crashes.compareAndSet(0, 1)) {
                                victim.set(c.workerId());
                                pool.crash(c.workerId());
                                Thread.sleep(5_000);
                            }
                            return recordEffect(c);
                        }));
        awaitAllInState(JobState.COMPLETED, count, Duration.ofSeconds(60));
        assertThat(victim.get()).isNotNull();
        assertThat(pool.alive()).doesNotContain(victim.get());
        assertThat(fixtures.count("select count(*) from effect")).isEqualTo(count);
        assertThat(fixtures.count("select count(*) from job_event where kind = 'LEASE_RECLAIMED'"))
                .isGreaterThanOrEqualTo(1);
        assertThat(fixtures.count("select count(*) from dead_letter")).isZero();
    }

    @Test
    void severalCrashesStillLoseNothing() {
        int count = 30;
        for (int i = 0; i < count; i++) {
            fixtures.job(fixtures.repository());
        }
        AtomicInteger seen = new AtomicInteger();
        ConcurrentMap<String, Boolean> crashed = new ConcurrentHashMap<>();
        start(
                6,
                pipeline(
                        c -> {
                            int n = seen.incrementAndGet();
                            if (n % 7 == 0 && crashed.putIfAbsent(c.workerId(), true) == null) {
                                pool.crash(c.workerId());
                                Thread.sleep(5_000);
                            }
                            return recordEffect(c);
                        }));
        awaitAllInState(JobState.COMPLETED, count, Duration.ofSeconds(90));
        assertThat(crashed).isNotEmpty();
        assertThat(fixtures.count("select count(*) from effect")).isEqualTo(count);
    }

    @Test
    void gracefulShutdownReleasesLeasesSoOthersCanContinueAtOnce() throws Exception {
        UUID repository = fixtures.repository();
        UUID id = fixtures.job(repository);
        AtomicInteger entered = new AtomicInteger();
        runner =
                new JobRunner(
                        jobs,
                        List.of(
                                handler(
                                        JobState.INDEXING,
                                        c -> {
                                            entered.incrementAndGet();
                                            Thread.sleep(30_000);
                                            return StepResult.advance(JobState.PROPOSING);
                                        })),
                        CONFIG,
                        WorkflowMetrics.NOOP,
                        RandomGenerator.of("L64X128MixRandom"));
        pool = new WorkerPool(runner, "w", 1, Duration.ofMillis(25));
        pool.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> entered.get() == 1);
        pool.stop(Duration.ofMillis(200));
        Job released = jobs.find(id).orElseThrow();
        assertThat(released.leased()).isFalse();
        assertThat(jobs.claim("other", CONFIG.lease())).isPresent();
    }

    @Test
    void poolsReportTheirWorkers() {
        start(3, pipeline(this::recordEffect));
        assertThat(pool.workerIds()).containsExactly("w-0", "w-1", "w-2");
        await().atMost(Duration.ofSeconds(5)).until(() -> pool.alive().size() == 3);
        assertThat(Map.of("processed", pool.processed()).get("processed")).isEqualTo(0);
    }

    @Test
    void theCrashedWorkersJobKeepsItsStateUntilTheLeaseExpires() {
        UUID id = fixtures.job(fixtures.repository());
        AtomicInteger runs = new AtomicInteger();
        List<StepHandler> handlers = new ArrayList<>(pipeline(this::recordEffect));
        handlers.set(
                0,
                handler(
                        JobState.INDEXING,
                        c -> {
                            if (runs.incrementAndGet() == 1) {
                                pool.crash(c.workerId());
                                Thread.sleep(5_000);
                            }
                            return StepResult.advance(JobState.PROPOSING);
                        }));
        start(2, handlers);
        await().atMost(Duration.ofSeconds(5)).until(() -> runs.get() >= 1);
        Job during = jobs.find(id).orElseThrow();
        assertThat(during.state()).isEqualTo(JobState.INDEXING);
        assertThat(during.leased()).isTrue();
        awaitAllInState(JobState.COMPLETED, 1, Duration.ofSeconds(30));
        assertThat(jobs.find(id).orElseThrow().attempt()).isZero();
    }
}
