package dev.rook.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.job.JobState;
import dev.rook.workflow.RunResult.Outcome;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobRunnerIT {

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

    private static final RunnerConfig CONFIG =
            new RunnerConfig(
                    Duration.ofSeconds(30),
                    Duration.ofMillis(40),
                    Duration.ofSeconds(20),
                    new Backoff(Duration.ofSeconds(4), 2.0, Duration.ofMinutes(1)));

    private WorkflowFixtures fixtures;
    private JobRepository jobs;
    private final List<JobRunner> runners = new ArrayList<>();
    private final List<String> metricLog = new ArrayList<>();
    private final WorkflowMetrics metrics =
            new WorkflowMetrics() {
                @Override
                public void step(JobState state, String outcome, Duration took) {
                    metricLog.add(state + ":" + outcome);
                }

                @Override
                public void leaseLost(JobState state) {
                    metricLog.add(state + ":leaseLost");
                }

                @Override
                public void claimed(boolean reclaimed) {
                    metricLog.add(reclaimed ? "reclaimed" : "claimed");
                }
            };

    @BeforeEach
    void setUp() {
        fixtures = new WorkflowFixtures();
        jobs = fixtures.jobs;
    }

    @AfterEach
    void tearDown() {
        runners.forEach(JobRunner::close);
    }

    private JobRunner runner(RunnerConfig config, StepHandler... handlers) {
        JobRunner runner =
                new JobRunner(
                        jobs,
                        List.of(handlers),
                        config,
                        metrics,
                        RandomGenerator.of("L64X128MixRandom"));
        runners.add(runner);
        return runner;
    }

    private JobRunner runner(StepHandler... handlers) {
        return runner(CONFIG, handlers);
    }

    private static List<StepHandler> happyPath() {
        return List.of(
                handler(
                        JobState.INDEXING,
                        c -> StepResult.advance(JobState.PROPOSING, Map.of("impact", "found"))),
                handler(
                        JobState.PROPOSING,
                        c -> StepResult.advance(JobState.VERIFYING, Map.of("patch", "diff"))),
                handler(JobState.VERIFYING, c -> StepResult.advance(JobState.PUBLISHING)),
                handler(
                        JobState.PUBLISHING,
                        c -> StepResult.advance(JobState.COMPLETED, Map.of("pr", 42))));
    }

    @Test
    void aJobWalksTheWholeHappyPathAndAccumulatesItsCheckpoint() {
        UUID id = fixtures.job(fixtures.repository());
        JobRunner runner = runner(happyPath().toArray(new StepHandler[0]));
        List<JobState> visited = new ArrayList<>();
        RunResult result;
        while (!(result = runner.runOnce("w1")).idleRun()) {
            visited.add(result.state());
        }
        assertThat(visited)
                .containsExactly(
                        JobState.PROPOSING,
                        JobState.VERIFYING,
                        JobState.PUBLISHING,
                        JobState.COMPLETED);
        Job done = jobs.find(id).orElseThrow();
        assertThat(done.state()).isEqualTo(JobState.COMPLETED);
        assertThat(done.finishedAt()).isNotNull();
        assertThat(done.checkpoint().path("impact").asString()).isEqualTo("found");
        assertThat(done.checkpoint().path("patch").asString()).isEqualTo("diff");
        assertThat(done.checkpoint().path("pr").asInt()).isEqualTo(42);
        assertThat(jobs.events(id, 0, 100))
                .extracting(JobEvent::kind)
                .contains("STEP_STARTED", "STATE_CHANGED", "LEASE_ACQUIRED");
        assertThat(metricLog).contains("INDEXING:advanced", "PUBLISHING:advanced", "claimed");
    }

    @Test
    void anIdleRunnerClaimsNothing() {
        assertThat(runner(happyPath().toArray(new StepHandler[0])).runOnce("w1").outcome())
                .isEqualTo(Outcome.IDLE);
    }

    @Test
    void impactAnalysisCanFinishAJobWithoutEdits() {
        UUID id = fixtures.job(fixtures.repository());
        JobRunner runner =
                runner(
                        handler(
                                JobState.INDEXING,
                                c ->
                                        StepResult.finish(
                                                JobState.NO_IMPACT,
                                                "nothing uses the changed api")));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.FINISHED);
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.state()).isEqualTo(JobState.NO_IMPACT);
        assertThat(job.lastError()).isEqualTo("nothing uses the changed api");
    }

    @Test
    void retryableFailuresAreRescheduledWithBackoffAndEventuallySucceed() {
        UUID id = fixtures.job(fixtures.repository());
        AtomicInteger calls = new AtomicInteger();
        JobRunner runner =
                runner(
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    if (calls.incrementAndGet() < 3) {
                                        throw new IOException("network blip " + calls.get());
                                    }
                                    return StepResult.advance(JobState.PROPOSING);
                                }));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        Job afterFirst = jobs.find(id).orElseThrow();
        assertThat(afterFirst.attempt()).isEqualTo(1);
        assertThat(afterFirst.lastError()).contains("network blip 1");
        assertThat(afterFirst.nextRunAt()).isAfter(fixtures.clock.instant());
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.IDLE);
        fixtures.testClock().advance(Duration.ofMinutes(2));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        fixtures.testClock().advance(Duration.ofMinutes(2));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.ADVANCED);
        Job after = jobs.find(id).orElseThrow();
        assertThat(after.state()).isEqualTo(JobState.PROPOSING);
        assertThat(after.attempt()).isZero();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(jobs.events(id, 0, 100))
                .extracting(JobEvent::kind)
                .contains("STEP_FAILED", "RETRY_SCHEDULED");
    }

    @Test
    void jobsThatKeepFailingEndUpDeadWithADeadLetter() {
        UUID id = fixtures.job(fixtures.repository());
        AtomicInteger calls = new AtomicInteger();
        JobRunner runner =
                runner(
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    calls.incrementAndGet();
                                    throw new IllegalStateException("always broken");
                                }));
        Outcome last = Outcome.IDLE;
        for (int i = 0; i < 10 && last != Outcome.DEAD; i++) {
            fixtures.testClock().advance(Duration.ofMinutes(5));
            last = runner.runOnce("w1").outcome();
        }
        assertThat(last).isEqualTo(Outcome.DEAD);
        Job dead = jobs.find(id).orElseThrow();
        assertThat(dead.state()).isEqualTo(JobState.DEAD);
        assertThat(dead.lastError()).isEqualTo("always broken");
        assertThat(calls.get()).isEqualTo(dead.maxAttempts());
        assertThat(fixtures.count("select count(*) from dead_letter")).isEqualTo(1);
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.IDLE);
    }

    @Test
    void fatalFailuresFailTheJobImmediatelyWithoutADeadLetter() {
        UUID id = fixtures.job(fixtures.repository());
        JobRunner runner =
                runner(
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    throw new StepFailure.Fatal("repository cannot be built");
                                }));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.FAILED);
        Job failed = jobs.find(id).orElseThrow();
        assertThat(failed.state()).isEqualTo(JobState.FAILED);
        assertThat(failed.lastError()).isEqualTo("repository cannot be built");
        assertThat(fixtures.count("select count(*) from dead_letter")).isZero();
    }

    @Test
    void suggestedDelaysOverrideTheBackoff() {
        UUID id = fixtures.job(fixtures.repository());
        JobRunner runner =
                runner(
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    throw new StepFailure.Retryable(
                                            "rate limited", null, Duration.ofMinutes(30));
                                }));
        runner.runOnce("w1");
        assertThat(jobs.find(id).orElseThrow().nextRunAt())
                .isEqualTo(fixtures.clock.instant().plus(Duration.ofMinutes(30)));
    }

    @Test
    void handlersCanAskForARetryWithoutThrowing() {
        UUID id = fixtures.job(fixtures.repository());
        JobRunner runner =
                runner(
                        handler(
                                JobState.INDEXING,
                                c -> StepResult.retryIn(Duration.ofMinutes(1), "checks pending")));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(jobs.find(id).orElseThrow().lastError()).isEqualTo("checks pending");
    }

    @Test
    void stepsThatRunTooLongAreTimedOutAndRetried() {
        UUID id = fixtures.job(fixtures.repository());
        RunnerConfig impatient = CONFIG.withStepTimeout(Duration.ofMillis(250));
        JobRunner runner =
                runner(
                        impatient,
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    Thread.sleep(10_000);
                                    return StepResult.advance(JobState.PROPOSING);
                                }));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(jobs.find(id).orElseThrow().lastError()).contains("exceeded");
    }

    @Test
    void aStateWithoutAHandlerIsRetriedNotDropped() {
        UUID id = fixtures.job(fixtures.repository());
        JobRunner runner =
                runner(handler(JobState.PROPOSING, c -> StepResult.advance(JobState.VERIFYING)));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(jobs.find(id).orElseThrow().lastError()).contains("no handler");
    }

    @Test
    void aWorkerThatLosesItsLeaseHasItsResultDiscarded() throws Exception {
        UUID id = fixtures.job(fixtures.repository());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        JobRunner slow =
                runner(
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    started.countDown();
                                    try {
                                        release.await(30, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        interrupted.incrementAndGet();
                                        throw e;
                                    }
                                    return StepResult.advance(
                                            JobState.PROPOSING, Map.of("stale", true));
                                }));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<RunResult> run = pool.submit(() -> slow.runOnce("slow"));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            fixtures.testClock().advance(CONFIG.lease().plusSeconds(5));
            JobRepository.Claim takeover = jobs.claim("fast", CONFIG.lease()).orElseThrow();
            assertThat(takeover.reclaimed()).isTrue();
            RunResult result = run.get(10, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(Outcome.LEASE_LOST);
            release.countDown();
        }
        Job job = jobs.find(id).orElseThrow();
        assertThat(job.leaseOwner()).isEqualTo("fast");
        assertThat(job.checkpoint().has("stale")).isFalse();
        assertThat(metricLog).contains("INDEXING:leaseLost");
        assertThat(interrupted.get()).isEqualTo(1);
    }

    @Test
    void repeatedCrashesEventuallyKillAPoisonJobWithoutRunningIt() {
        UUID id = fixtures.job(fixtures.repository());
        Job first = jobs.claim("crasher", CONFIG.lease()).orElseThrow().job();
        assertThat(first.attempt()).isZero();
        for (int i = 0; i < first.maxAttempts(); i++) {
            fixtures.testClock().advance(CONFIG.lease().plusSeconds(1));
            assertThat(jobs.claim("crasher", CONFIG.lease())).isPresent();
        }
        fixtures.testClock().advance(CONFIG.lease().plusSeconds(1));
        AtomicInteger executions = new AtomicInteger();
        JobRunner runner =
                runner(
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    executions.incrementAndGet();
                                    return StepResult.advance(JobState.PROPOSING);
                                }));
        assertThat(runner.runOnce("survivor").outcome()).isEqualTo(Outcome.DEAD);
        assertThat(executions.get()).isZero();
        assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.DEAD);
        assertThat(fixtures.count("select count(*) from dead_letter")).isEqualTo(1);
    }

    @Test
    void longRunningStepsKeepTheirLeaseThroughAutomaticHeartbeats() throws Exception {
        UUID id = fixtures.job(fixtures.repository());
        RunnerConfig config = CONFIG.withHeartbeat(Duration.ofMillis(30));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobRunner runner =
                runner(
                        config,
                        handler(
                                JobState.INDEXING,
                                c -> {
                                    started.countDown();
                                    release.await(30, TimeUnit.SECONDS);
                                    return StepResult.advance(JobState.PROPOSING);
                                }));
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<RunResult> run = pool.submit(() -> runner.runOnce("w1"));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            fixtures.testClock().advance(Duration.ofSeconds(20));
            Thread.sleep(200);
            fixtures.testClock().advance(Duration.ofSeconds(20));
            Thread.sleep(200);
            assertThat(jobs.claim("other", CONFIG.lease())).isEmpty();
            release.countDown();
            assertThat(run.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.ADVANCED);
        }
        assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.PROPOSING);
    }

    @Test
    void approvalGatesPublishingUntilAReviewerApproves() {
        UUID id = fixtures.job(fixtures.repository());
        List<StepHandler> handlers = new ArrayList<>(happyPath());
        handlers.set(
                2,
                handler(JobState.VERIFYING, c -> StepResult.advance(JobState.AWAITING_APPROVAL)));
        JobRunner runner = runner(handlers.toArray(new StepHandler[0]));
        while (jobs.find(id).orElseThrow().state() != JobState.AWAITING_APPROVAL) {
            assertThat(runner.runOnce("w1").idleRun()).isFalse();
        }
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(Outcome.IDLE);
        jobs.recordApproval(id, true, "reviewer");
        assertThat(runner.runOnce("w1").state()).isEqualTo(JobState.COMPLETED);
        assertThat(jobs.find(id).orElseThrow().checkpoint().path("approval").asString())
                .isEqualTo("APPROVED");
    }

    @Test
    void duplicateHandlersForOneStateAreRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                runner(
                                        handler(
                                                JobState.INDEXING,
                                                c -> StepResult.advance(JobState.PROPOSING)),
                                        handler(
                                                JobState.INDEXING,
                                                c -> StepResult.advance(JobState.PROPOSING))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
