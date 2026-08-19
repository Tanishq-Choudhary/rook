package dev.rook.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.job.JobState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobRepositoryIT {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private WorkflowFixtures fixtures;
    private JobRepository jobs;

    @BeforeEach
    void setUp() {
        fixtures = new WorkflowFixtures();
        jobs = fixtures.jobs;
    }

    @Test
    void creatingTheSameJobTwiceIsIdempotent() {
        UUID changeSet = fixtures.changeSet();
        UUID repository = fixtures.repository();
        JobRepository.CreateResult first = jobs.create(changeSet, repository, 0, 5);
        JobRepository.CreateResult second = jobs.create(changeSet, repository, 0, 5);
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(fixtures.count("select count(*) from migration_job")).isEqualTo(1);
        assertThat(jobs.events(first.jobId(), 0, 10))
                .extracting(JobEvent::kind)
                .containsExactly("JOB_CREATED");
    }

    @Test
    void claimingStartsAPendingJobAndFencesIt() {
        UUID id = fixtures.job(fixtures.repository());
        JobRepository.Claim claim = jobs.claim("w1", LEASE).orElseThrow();
        Job job = claim.job();
        assertThat(job.id()).isEqualTo(id);
        assertThat(job.state()).isEqualTo(JobState.INDEXING);
        assertThat(job.leaseOwner()).isEqualTo("w1");
        assertThat(job.fence()).isEqualTo(1);
        assertThat(job.leaseExpiresAt()).isEqualTo(fixtures.clock.instant().plus(LEASE));
        assertThat(claim.reclaimed()).isFalse();
        assertThat(jobs.claim("w2", LEASE)).isEmpty();
        assertThat(jobs.events(id, 0, 10))
                .extracting(JobEvent::kind)
                .containsExactly("JOB_CREATED", "STATE_CHANGED", "LEASE_ACQUIRED");
    }

    @Test
    void jobsScheduledForLaterAreNotClaimable() {
        UUID id = fixtures.job(fixtures.repository());
        Job first = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThat(jobs.retry(id, first.fence(), "boom", Duration.ofMinutes(5))).isEqualTo(1);
        assertThat(jobs.claim("w1", LEASE)).isEmpty();
        fixtures.testClock().advance(Duration.ofMinutes(5));
        Job again = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThat(again.id()).isEqualTo(id);
        assertThat(again.attempt()).isEqualTo(1);
        assertThat(again.lastError()).isEqualTo("boom");
        assertThat(again.fence()).isEqualTo(2);
    }

    @Test
    void expiredLeasesAreReclaimedAndCountAsAnAttempt() {
        UUID id = fixtures.job(fixtures.repository());
        Job first = jobs.claim("crashed", LEASE).orElseThrow().job();
        fixtures.testClock().advance(LEASE.minusSeconds(1));
        assertThat(jobs.claim("w2", LEASE)).isEmpty();
        fixtures.testClock().advance(Duration.ofSeconds(2));
        JobRepository.Claim claim = jobs.claim("w2", LEASE).orElseThrow();
        assertThat(claim.reclaimed()).isTrue();
        assertThat(claim.previousOwner()).isEqualTo("crashed");
        assertThat(claim.job().fence()).isEqualTo(first.fence() + 1);
        assertThat(claim.job().attempt()).isEqualTo(1);
        assertThat(claim.job().state()).isEqualTo(JobState.INDEXING);
        assertThat(jobs.events(id, 0, 20)).extracting(JobEvent::kind).contains("LEASE_RECLAIMED");
    }

    @Test
    void higherPriorityJobsGoFirstThenTheOldest() {
        UUID low = fixtures.job(fixtures.repository(), 0);
        fixtures.testClock().advance(Duration.ofSeconds(1));
        UUID high = fixtures.job(fixtures.repository(), 5);
        fixtures.testClock().advance(Duration.ofSeconds(1));
        UUID lowLater = fixtures.job(fixtures.repository(), 0);
        List<UUID> order = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            order.add(jobs.claim("w", LEASE).orElseThrow().job().id());
        }
        assertThat(order).containsExactly(high, low, lowLater);
    }

    @Test
    void aRepositoryRunsOneJobAtATime() {
        UUID repository = fixtures.repository();
        UUID first = fixtures.job(repository);
        fixtures.testClock().advance(Duration.ofSeconds(1));
        UUID second = fixtures.job(repository);
        Job running = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThat(running.id()).isEqualTo(first);
        assertThat(jobs.claim("w2", LEASE)).isEmpty();
        assertThat(
                        jobs.advance(
                                first,
                                running.fence(),
                                JobState.INDEXING,
                                JobState.NO_IMPACT,
                                Map.of(),
                                Map.of()))
                .isTrue();
        Job next = jobs.claim("w2", LEASE).orElseThrow().job();
        assertThat(next.id()).isEqualTo(second);
    }

    @Test
    void waitingForApprovalReleasesTheRepository() {
        UUID repository = fixtures.repository();
        UUID first = fixtures.job(repository);
        Job running = jobs.claim("w1", LEASE).orElseThrow().job();
        advanceTo(first, running.fence(), JobState.INDEXING, JobState.PROPOSING);
        Job proposing = jobs.claim("w1", LEASE).orElseThrow().job();
        advanceTo(first, proposing.fence(), JobState.PROPOSING, JobState.VERIFYING);
        Job verifying = jobs.claim("w1", LEASE).orElseThrow().job();
        advanceTo(first, verifying.fence(), JobState.VERIFYING, JobState.AWAITING_APPROVAL);
        fixtures.testClock().advance(Duration.ofSeconds(1));
        UUID second = fixtures.job(repository);
        assertThat(jobs.claim("w2", LEASE).orElseThrow().job().id()).isEqualTo(second);
    }

    private void advanceTo(UUID id, long fence, JobState from, JobState to) {
        assertThat(jobs.advance(id, fence, from, to, Map.of(), Map.of())).isTrue();
    }

    @Test
    void approvedJobsResumeAtPublishingWhenTheRepositoryIsFree() {
        UUID repository = fixtures.repository();
        UUID id = fixtures.job(repository);
        Job job = jobs.claim("w", LEASE).orElseThrow().job();
        advanceTo(id, job.fence(), JobState.INDEXING, JobState.PROPOSING);
        job = jobs.claim("w", LEASE).orElseThrow().job();
        advanceTo(id, job.fence(), JobState.PROPOSING, JobState.VERIFYING);
        job = jobs.claim("w", LEASE).orElseThrow().job();
        advanceTo(id, job.fence(), JobState.VERIFYING, JobState.AWAITING_APPROVAL);
        assertThat(jobs.claim("w", LEASE)).isEmpty();
        assertThat(jobs.recordApproval(id, true, "reviewer")).isTrue();
        Job resumed = jobs.claim("w", LEASE).orElseThrow().job();
        assertThat(resumed.state()).isEqualTo(JobState.PUBLISHING);
        assertThat(resumed.checkpointText("approval")).isEqualTo("APPROVED");
        assertThat(resumed.checkpointText("approvedBy")).isEqualTo("reviewer");
    }

    @Test
    void rejectionIsTerminalAndOnlyAppliesWhileAwaitingApproval() {
        UUID id = fixtures.job(fixtures.repository());
        assertThat(jobs.recordApproval(id, false, "reviewer")).isFalse();
        Job job = jobs.claim("w", LEASE).orElseThrow().job();
        advanceTo(id, job.fence(), JobState.INDEXING, JobState.PROPOSING);
        job = jobs.claim("w", LEASE).orElseThrow().job();
        advanceTo(id, job.fence(), JobState.PROPOSING, JobState.VERIFYING);
        job = jobs.claim("w", LEASE).orElseThrow().job();
        advanceTo(id, job.fence(), JobState.VERIFYING, JobState.AWAITING_APPROVAL);
        assertThat(jobs.recordApproval(id, false, "reviewer")).isTrue();
        Job rejected = jobs.find(id).orElseThrow();
        assertThat(rejected.state()).isEqualTo(JobState.REJECTED);
        assertThat(rejected.finishedAt()).isNotNull();
        assertThat(jobs.recordApproval(id, true, "reviewer")).isFalse();
    }

    @Test
    void heartbeatsExtendTheLeaseOnlyForTheCurrentFence() {
        UUID id = fixtures.job(fixtures.repository());
        Job job = jobs.claim("w1", LEASE).orElseThrow().job();
        fixtures.testClock().advance(Duration.ofSeconds(20));
        assertThat(jobs.heartbeat(id, job.fence(), LEASE)).isTrue();
        assertThat(jobs.find(id).orElseThrow().leaseExpiresAt())
                .isEqualTo(fixtures.clock.instant().plus(LEASE));
        assertThat(jobs.heartbeat(id, job.fence() + 1, LEASE)).isFalse();
        assertThat(jobs.heartbeat(UUID.randomUUID(), 1, LEASE)).isFalse();
    }

    @Test
    void aStaleWorkerCannotWriteAfterTheJobWasReclaimed() {
        UUID id = fixtures.job(fixtures.repository());
        Job stale = jobs.claim("w1", LEASE).orElseThrow().job();
        fixtures.testClock().advance(LEASE.plusSeconds(1));
        Job fresh = jobs.claim("w2", LEASE).orElseThrow().job();
        assertThat(
                        jobs.advance(
                                id,
                                stale.fence(),
                                JobState.INDEXING,
                                JobState.PROPOSING,
                                Map.of("late", true),
                                Map.of()))
                .isFalse();
        assertThat(jobs.retry(id, stale.fence(), "stale", Duration.ZERO)).isEqualTo(-1);
        assertThat(jobs.finish(id, stale.fence(), JobState.INDEXING, JobState.FAILED, "stale"))
                .isFalse();
        assertThat(jobs.release(id, stale.fence())).isFalse();
        Job current = jobs.find(id).orElseThrow();
        assertThat(current.state()).isEqualTo(JobState.INDEXING);
        assertThat(current.leaseOwner()).isEqualTo("w2");
        assertThat(current.checkpoint().has("late")).isFalse();
        assertThat(
                        jobs.advance(
                                id,
                                fresh.fence(),
                                JobState.INDEXING,
                                JobState.PROPOSING,
                                Map.of("on", "time"),
                                Map.of()))
                .isTrue();
    }

    @Test
    void advancingMergesTheCheckpointResetsAttemptsAndReleasesTheLease() {
        UUID id = fixtures.job(fixtures.repository());
        Job first = jobs.claim("w1", LEASE).orElseThrow().job();
        jobs.retry(id, first.fence(), "flaky", Duration.ZERO);
        Job second = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThat(second.attempt()).isEqualTo(1);
        assertThat(
                        jobs.advance(
                                id,
                                second.fence(),
                                JobState.INDEXING,
                                JobState.PROPOSING,
                                Map.of("impact", "x"),
                                Map.of("note", "indexed")))
                .isTrue();
        Job after = jobs.find(id).orElseThrow();
        assertThat(after.state()).isEqualTo(JobState.PROPOSING);
        assertThat(after.attempt()).isZero();
        assertThat(after.leased()).isFalse();
        assertThat(after.lastError()).isNull();
        assertThat(after.checkpointText("impact")).isEqualTo("x");
        jobs.claim("w1", LEASE)
                .ifPresent(
                        c ->
                                jobs.advance(
                                        id,
                                        c.job().fence(),
                                        JobState.PROPOSING,
                                        JobState.VERIFYING,
                                        Map.of("patch", "y"),
                                        Map.of()));
        Job merged = jobs.find(id).orElseThrow();
        assertThat(merged.checkpointText("impact")).isEqualTo("x");
        assertThat(merged.checkpointText("patch")).isEqualTo("y");
    }

    @Test
    void illegalTransitionsAreRejectedBeforeTouchingTheDatabase() {
        UUID id = fixtures.job(fixtures.repository());
        Job job = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThatThrownBy(
                        () ->
                                jobs.advance(
                                        id,
                                        job.fence(),
                                        JobState.INDEXING,
                                        JobState.COMPLETED,
                                        Map.of(),
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                jobs.finish(
                                        id,
                                        job.fence(),
                                        JobState.INDEXING,
                                        JobState.PROPOSING,
                                        "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.INDEXING);
    }

    @Test
    void advancingFromTheWrongStateDoesNothing() {
        UUID id = fixtures.job(fixtures.repository());
        Job job = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThat(
                        jobs.advance(
                                id,
                                job.fence(),
                                JobState.PROPOSING,
                                JobState.VERIFYING,
                                Map.of(),
                                Map.of()))
                .isFalse();
    }

    @Test
    void deadJobsLeaveADeadLetterAndFailedJobsDoNot() {
        UUID dead = fixtures.job(fixtures.repository());
        Job deadJob = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThat(
                        jobs.finish(
                                dead,
                                deadJob.fence(),
                                JobState.INDEXING,
                                JobState.DEAD,
                                "exhausted"))
                .isTrue();
        UUID failed = fixtures.job(fixtures.repository());
        Job failedJob = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThat(
                        jobs.finish(
                                failed,
                                failedJob.fence(),
                                JobState.INDEXING,
                                JobState.FAILED,
                                "invalid"))
                .isTrue();
        assertThat(fixtures.count("select count(*) from dead_letter")).isEqualTo(1);
        assertThat(fixtures.jdbc.sql("select reason from dead_letter").query(String.class).single())
                .isEqualTo("exhausted");
        assertThat(jobs.find(dead).orElseThrow().state()).isEqualTo(JobState.DEAD);
        assertThat(jobs.find(dead).orElseThrow().finishedAt()).isNotNull();
    }

    @Test
    void releasingMakesTheJobClaimableAtOnce() {
        UUID id = fixtures.job(fixtures.repository());
        Job job = jobs.claim("w1", LEASE).orElseThrow().job();
        assertThat(jobs.release(id, job.fence())).isTrue();
        JobRepository.Claim claim = jobs.claim("w2", LEASE).orElseThrow();
        assertThat(claim.reclaimed()).isFalse();
        assertThat(claim.job().fence()).isEqualTo(job.fence() + 1);
    }

    @Test
    void everyJobIsClaimedExactlyOnceUnderContention() throws Exception {
        int jobCount = 120;
        for (int i = 0; i < jobCount; i++) {
            fixtures.job(fixtures.repository());
        }
        int workers = 12;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<List<UUID>>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                String owner = "worker-" + w;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    List<UUID> mine = new ArrayList<>();
                                    Optional<JobRepository.Claim> claim;
                                    while ((claim = jobs.claim(owner, LEASE)).isPresent()) {
                                        mine.add(claim.get().job().id());
                                    }
                                    return mine;
                                }));
            }
            start.countDown();
            List<UUID> all = new ArrayList<>();
            for (Future<List<UUID>> future : futures) {
                all.addAll(future.get());
            }
            assertThat(all).hasSize(jobCount);
            assertThat(new HashSet<>(all)).hasSize(jobCount);
        }
    }

    @Test
    void contendingJobsOfOneRepositoryAreHandedOutOneAtATime() throws Exception {
        UUID repository = fixtures.repository();
        for (int i = 0; i < 20; i++) {
            fixtures.job(repository);
            fixtures.testClock().advance(Duration.ofMillis(5));
        }
        int workers = 10;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<Optional<JobRepository.Claim>>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                String owner = "worker-" + w;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return jobs.claim(owner, LEASE);
                                }));
            }
            start.countDown();
            Set<UUID> claimed = new HashSet<>();
            for (Future<Optional<JobRepository.Claim>> future : futures) {
                future.get().ifPresent(c -> claimed.add(c.job().id()));
            }
            assertThat(claimed).hasSize(1);
        }
        assertThat(fixtures.count("select count(*) from migration_job where state = 'INDEXING'"))
                .isEqualTo(1);
    }

    @Test
    void countsByStateIncludeEveryState() {
        fixtures.job(fixtures.repository());
        fixtures.job(fixtures.repository());
        jobs.claim("w", LEASE);
        Map<JobState, Long> counts = jobs.countByState();
        assertThat(counts).containsKeys(JobState.values());
        assertThat(counts.get(JobState.PENDING)).isEqualTo(1);
        assertThat(counts.get(JobState.INDEXING)).isEqualTo(1);
        assertThat(counts.get(JobState.COMPLETED)).isZero();
    }

    @Test
    void eventsAreReadInOrderAfterACursor() {
        UUID id = fixtures.job(fixtures.repository());
        Job job = jobs.claim("w1", LEASE).orElseThrow().job();
        jobs.advance(id, job.fence(), JobState.INDEXING, JobState.PROPOSING, Map.of(), Map.of());
        List<JobEvent> all = jobs.events(id, 0, 50);
        assertThat(all).extracting(JobEvent::id).isSorted();
        long cursor = all.get(1).id();
        assertThat(jobs.events(id, cursor, 50)).hasSize(all.size() - 2);
        assertThat(jobs.events(id, 0, 2)).hasSize(2);
    }
}
