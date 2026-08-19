package dev.rook.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.random.RandomGenerator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private final Backoff backoff = new Backoff(Duration.ofSeconds(5), 2.0, Duration.ofMinutes(10));

    @Test
    void ceilingsGrowExponentiallyUntilTheCap() {
        assertThat(backoff.ceiling(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(backoff.ceiling(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.ceiling(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(backoff.ceiling(8)).isEqualTo(Duration.ofSeconds(600));
        assertThat(backoff.ceiling(40)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void delaysStayBetweenHalfTheCeilingAndTheCeiling() {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        for (int attempt = 1; attempt <= 12; attempt++) {
            for (int i = 0; i < 200; i++) {
                Duration delay = backoff.delay(attempt, random);
                assertThat(delay)
                        .isBetween(backoff.ceiling(attempt).dividedBy(2), backoff.ceiling(attempt));
            }
        }
    }

    @Test
    void jitterActuallyVaries() {
        RandomGenerator random = RandomGenerator.of("L64X128MixRandom");
        long distinct =
                java.util.stream.IntStream.range(0, 100)
                        .mapToObj(i -> backoff.delay(5, random))
                        .distinct()
                        .count();
        assertThat(distinct).isGreaterThan(50);
    }

    @Test
    void firstAttemptNeverWaitsLessThanHalfTheBase() {
        RandomGenerator zero =
                new RandomGenerator() {
                    @Override
                    public long nextLong() {
                        return 0;
                    }

                    @Override
                    public long nextLong(long bound) {
                        return 0;
                    }
                };
        assertThat(backoff.delay(1, zero)).isEqualTo(Duration.ofMillis(2500));
    }

    @Test
    void invalidConfigurationsAreRejected() {
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, 2.0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(1), 0.5, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofMinutes(2), 2.0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void ceilingsNeverShrinkAndNeverExceedTheCap(
            @ForAll @IntRange(min = 1, max = 500) int attempt) {
        assertThat(backoff.ceiling(attempt + 1)).isGreaterThanOrEqualTo(backoff.ceiling(attempt));
        assertThat(backoff.ceiling(attempt)).isLessThanOrEqualTo(Duration.ofMinutes(10));
    }
}
