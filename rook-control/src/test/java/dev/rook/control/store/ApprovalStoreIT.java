package dev.rook.control.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Reason;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalStoreIT {

    private static final Decision REVIEW =
            new Decision(
                    Decision.Outcome.REQUIRE_APPROVAL,
                    List.of(new Reason("LARGE_PATCH_LINES", "big")));

    private ControlFixtures fixtures;
    private ApprovalStore store;
    private UUID installation;
    private UUID repository;

    @BeforeEach
    void setUp() {
        fixtures = new ControlFixtures();
        store = new ApprovalStore(fixtures.jdbc, fixtures.clock);
        installation = fixtures.installation();
        repository = fixtures.repository(installation);
    }

    @Test
    void requestingTwiceKeepsTheFirstRequest() {
        UUID job = fixtures.job(repository);
        ApprovalStore.Approval first = store.request(job, REVIEW);
        fixtures.clock.advance(Duration.ofMinutes(5));
        ApprovalStore.Approval second = store.request(job, REVIEW);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.requestedAt()).isEqualTo(first.requestedAt());
        assertThat(second.status()).isEqualTo(ApprovalStore.Status.PENDING);
        assertThat(second.reasons()).containsExactly(new Reason("LARGE_PATCH_LINES", "big"));
    }

    @Test
    void aReviewerDecidesOnceAndTheDecisionIsRecorded() {
        UUID job = fixtures.job(repository);
        store.request(job, REVIEW);
        fixtures.clock.advance(Duration.ofMinutes(2));
        assertThat(store.decide(job, true, "reviewer-1", "looks right")).isTrue();
        assertThat(store.decide(job, false, "reviewer-2", "changed my mind")).isFalse();
        ApprovalStore.Approval approval = store.find(job).orElseThrow();
        assertThat(approval.status()).isEqualTo(ApprovalStore.Status.APPROVED);
        assertThat(approval.decidedBy()).isEqualTo("reviewer-1");
        assertThat(approval.note()).isEqualTo("looks right");
        assertThat(approval.decidedAt()).isAfter(approval.requestedAt());
    }

    @Test
    void rejectionsAreRecordedToo() {
        UUID job = fixtures.job(repository);
        store.request(job, REVIEW);
        assertThat(store.decide(job, false, "reviewer-1", "wrong approach")).isTrue();
        assertThat(store.find(job).orElseThrow().status()).isEqualTo(ApprovalStore.Status.REJECTED);
    }

    @Test
    void decidingWithoutARequestOrAReviewerFails() {
        UUID job = fixtures.job(repository);
        assertThat(store.decide(job, true, "reviewer-1", null)).isFalse();
        store.request(job, REVIEW);
        assertThatThrownBy(() -> store.decide(job, true, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(job).orElseThrow().status()).isEqualTo(ApprovalStore.Status.PENDING);
    }

    @Test
    void pendingListsOnlyOpenRequestsOfThatInstallationOldestFirst() {
        UUID first = fixtures.job(repository);
        store.request(first, REVIEW);
        fixtures.clock.advance(Duration.ofMinutes(1));
        UUID second = fixtures.job(repository);
        store.request(second, REVIEW);
        UUID decided = fixtures.job(repository);
        store.request(decided, REVIEW);
        store.decide(decided, true, "r", null);
        UUID strangerRepo = fixtures.repository(fixtures.installation());
        store.request(fixtures.job(strangerRepo), REVIEW);
        assertThat(store.pending(installation, 10))
                .extracting(ApprovalStore.Approval::jobId)
                .containsExactly(first, second);
        assertThat(store.pending(installation, 1)).hasSize(1);
    }

    @Test
    void concurrentReviewersCannotBothDecide() throws Exception {
        UUID job = fixtures.job(repository);
        store.request(job, REVIEW);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            int reviewer = i;
            pool.submit(
                    () -> {
                        start.await();
                        if (store.decide(job, reviewer % 2 == 0, "reviewer-" + reviewer, null)) {
                            winners.incrementAndGet();
                        }
                        return null;
                    });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(winners.get()).isEqualTo(1);
    }
}
