package dev.rook.control.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.control.store.ControlFixtures;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.core.id.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BudgetServiceIT {

    private ControlFixtures fixtures;
    private BudgetService budgets;
    private UUID installation;

    @BeforeEach
    void setUp() {
        fixtures = new ControlFixtures();
        budgets =
                new BudgetService(
                        fixtures.jdbc,
                        fixtures.tx,
                        fixtures.clock,
                        new BudgetService.Caps(1_000, 400));
        installation = fixtures.installation();
    }

    @Test
    void withoutABudgetRowTheDefaultCapsApply() {
        assertThat(budgets.caps(installation)).isEqualTo(new BudgetService.Caps(1_000, 400));
        budgets.setBudget(installation, new BudgetService.Caps(5_000, 2_000));
        assertThat(budgets.caps(installation)).isEqualTo(new BudgetService.Caps(5_000, 2_000));
        budgets.setBudget(installation, new BudgetService.Caps(7_000, 3_000));
        assertThat(budgets.caps(installation).monthlyMicros()).isEqualTo(7_000);
    }

    @Test
    void reservationsFillTheMonthlyBudgetExactlyAndThenRefuse() {
        for (int i = 0; i < 10; i++) {
            budgets.reserve(installation, null, SpendKind.MODEL, 100);
        }
        assertThatThrownBy(() -> budgets.reserve(installation, null, SpendKind.MODEL, 1))
                .isInstanceOf(BudgetExceededException.class)
                .satisfies(
                        e -> {
                            BudgetExceededException budget = (BudgetExceededException) e;
                            assertThat(budget.scope())
                                    .isEqualTo(BudgetExceededException.Scope.MONTHLY);
                            assertThat(budget.code()).isEqualTo(ErrorCode.BUDGET_EXCEEDED);
                        });
        assertThat(budgets.usage(installation).remainingMicros()).isZero();
    }

    @Test
    void theJobCapStopsOneJobBeforeTheMonthlyCapDoes() {
        UUID job = Ids.newId();
        budgets.reserve(installation, job, SpendKind.MODEL, 300);
        assertThatThrownBy(() -> budgets.reserve(installation, job, SpendKind.SANDBOX, 150))
                .isInstanceOf(BudgetExceededException.class)
                .satisfies(
                        e ->
                                assertThat(((BudgetExceededException) e).scope())
                                        .isEqualTo(BudgetExceededException.Scope.JOB));
        assertThat(budgets.reserve(installation, Ids.newId(), SpendKind.MODEL, 400).micros())
                .isEqualTo(400);
    }

    @Test
    void settlingChargesTheActualCostAndFreesTheRest() {
        UUID job = Ids.newId();
        BudgetService.Reservation reservation =
                budgets.reserve(installation, job, SpendKind.MODEL, 400);
        assertThat(budgets.settle(reservation.id(), 120, 5_000)).isTrue();
        assertThat(budgets.settle(reservation.id(), 120, 5_000)).isFalse();
        assertThat(budgets.jobSpend(job)).isEqualTo(120);
        BudgetService.Usage usage = budgets.usage(installation);
        assertThat(usage.spentMicros()).isEqualTo(120);
        assertThat(usage.reservedMicros()).isZero();
        assertThat(usage.spentByKind())
                .containsEntry(SpendKind.MODEL, 120L)
                .containsEntry(SpendKind.SANDBOX, 0L);
        assertThat(budgets.reserve(installation, job, SpendKind.MODEL, 280)).isNotNull();
        assertThat(
                        fixtures.jdbc
                                .sql("select units from usage_ledger where job_id = :j")
                                .param("j", job)
                                .query(Long.class)
                                .single())
                .isEqualTo(5_000);
    }

    @Test
    void releasingGivesTheReservationBack() {
        BudgetService.Reservation reservation =
                budgets.reserve(installation, null, SpendKind.SANDBOX, 900);
        assertThatThrownBy(() -> budgets.reserve(installation, null, SpendKind.SANDBOX, 200))
                .isInstanceOf(BudgetExceededException.class);
        assertThat(budgets.release(reservation.id())).isTrue();
        assertThat(budgets.release(reservation.id())).isFalse();
        assertThat(budgets.settle(reservation.id(), 5, 1)).isFalse();
        assertThat(budgets.reserve(installation, null, SpendKind.SANDBOX, 200)).isNotNull();
    }

    @Test
    void manyConcurrentSpendersCanNeverOverspend() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            pool.submit(
                    () -> {
                        start.await();
                        try {
                            budgets.reserve(installation, null, SpendKind.MODEL, 100);
                            granted.incrementAndGet();
                        } catch (BudgetExceededException e) {
                            refused.incrementAndGet();
                        }
                        return null;
                    });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(granted.get()).isEqualTo(10);
        assertThat(refused.get()).isEqualTo(30);
        assertThat(budgets.usage(installation).reservedMicros()).isEqualTo(1_000);
    }

    @Test
    void concurrentSpendersOnOneJobShareItsCap() throws Exception {
        UUID job = Ids.newId();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        for (int i = 0; i < 16; i++) {
            pool.submit(
                    () -> {
                        start.await();
                        try {
                            budgets.reserve(installation, job, SpendKind.MODEL, 100);
                            granted.incrementAndGet();
                        } catch (BudgetExceededException e) {
                            return null;
                        }
                        return null;
                    });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(granted.get()).isEqualTo(4);
    }

    @Test
    void theBudgetStartsOverEachCalendarMonth() {
        budgets.record(installation, null, SpendKind.MODEL, 1, 1_000);
        assertThatThrownBy(() -> budgets.reserve(installation, null, SpendKind.MODEL, 1))
                .isInstanceOf(BudgetExceededException.class);
        fixtures.clock.set(Instant.parse("2026-11-01T00:00:01Z"));
        assertThat(budgets.reserve(installation, null, SpendKind.MODEL, 1_000)).isNotNull();
        assertThat(budgets.usage(installation).monthStart())
                .isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(budgets.usage(installation).spentMicros()).isZero();
    }

    @Test
    void installationsDoNotShareBudgets() {
        UUID other = fixtures.installation();
        budgets.reserve(installation, null, SpendKind.MODEL, 1_000);
        assertThat(budgets.reserve(other, null, SpendKind.MODEL, 1_000)).isNotNull();
        assertThat(budgets.usage(other).reservedMicros()).isEqualTo(1_000);
    }

    @Test
    void forgottenReservationsAreReleasedAfterTheyGoStale() {
        budgets.reserve(installation, null, SpendKind.MODEL, 600);
        fixtures.clock.advance(Duration.ofMinutes(30));
        budgets.reserve(installation, null, SpendKind.MODEL, 300);
        assertThat(budgets.releaseOlderThan(Duration.ofMinutes(20))).isEqualTo(1);
        assertThat(budgets.usage(installation).reservedMicros()).isEqualTo(300);
    }

    @Test
    void badInputIsRejected() {
        assertThatThrownBy(() -> budgets.reserve(installation, null, SpendKind.MODEL, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budgets.settle(Ids.newId(), -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BudgetService.Caps(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budgets.reserve(Ids.newId(), null, SpendKind.MODEL, 1))
                .isInstanceOf(RookException.class)
                .satisfies(
                        e -> assertThat(((RookException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }
}
