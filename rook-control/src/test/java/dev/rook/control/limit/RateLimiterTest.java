package dev.rook.control.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.testkit.TestClock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final TestClock clock = TestClock.at("2026-10-01T00:00:00Z");

    @Test
    void aFreshKeyGetsItsFullBurstThenIsRefused() {
        RateLimiter limiter = new RateLimiter(clock, 5, 1, 100);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        }
        RateLimiter.Result refused = limiter.tryAcquire("k");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMillis()).isEqualTo(1000);
        assertThat(refused.remaining()).isZero();
    }

    @Test
    void tokensComeBackAtTheRefillRate() {
        RateLimiter limiter = new RateLimiter(clock, 2, 2, 100);
        limiter.tryAcquire("k");
        limiter.tryAcquire("k");
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
        clock.advance(Duration.ofMillis(500));
        assertThat(limiter.tryAcquire("k").allowed()).isTrue();
        assertThat(limiter.tryAcquire("k").allowed()).isFalse();
        clock.advance(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("k").remaining()).isEqualTo(1);
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(clock, 1, 1, 100);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void expensiveRequestsCostMoreTokens() {
        RateLimiter limiter = new RateLimiter(clock, 10, 1, 100);
        assertThat(limiter.tryAcquire("k", 6).allowed()).isTrue();
        RateLimiter.Result refused = limiter.tryAcquire("k", 6);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMillis()).isEqualTo(2000);
        assertThatThrownBy(() -> limiter.tryAcquire("k", 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("k", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNumberOfTrackedKeysIsBounded() {
        RateLimiter limiter = new RateLimiter(clock, 1, 1, 50);
        for (int i = 0; i < 500; i++) {
            limiter.tryAcquire("key-" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(50);
    }

    @Test
    void concurrentCallersNeverGetMoreThanTheBurst() throws Exception {
        RateLimiter limiter = new RateLimiter(clock, 25, 1, 100);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < 200; i++) {
            pool.submit(
                    () -> {
                        if (limiter.tryAcquire("shared").allowed()) {
                            allowed.incrementAndGet();
                        }
                    });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(allowed.get()).isEqualTo(25);
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> new RateLimiter(clock, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(clock, 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimiter(clock, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void overAnyWindowTheGrantedRequestsStayWithinBurstPlusRefill(
            @ForAll @IntRange(min = 1, max = 20) int capacity,
            @ForAll @IntRange(min = 1, max = 10) int perSecond,
            @ForAll @IntRange(min = 1, max = 300) int requests,
            @ForAll @IntRange(min = 1, max = 400) int stepMillis) {
        TestClock local = TestClock.at("2026-10-01T00:00:00Z");
        RateLimiter limiter = new RateLimiter(local, capacity, perSecond, 10);
        int granted = 0;
        for (int i = 0; i < requests; i++) {
            if (limiter.tryAcquire("k").allowed()) {
                granted++;
            }
            local.advance(Duration.ofMillis(stepMillis));
        }
        double seconds = requests * stepMillis / 1000.0;
        assertThat(granted).isLessThanOrEqualTo((int) Math.ceil(capacity + seconds * perSecond));
    }
}
