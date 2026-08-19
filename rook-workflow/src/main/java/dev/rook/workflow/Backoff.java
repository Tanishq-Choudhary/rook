package dev.rook.workflow;

import java.time.Duration;
import java.util.random.RandomGenerator;

public record Backoff(Duration base, double factor, Duration cap) {

    public Backoff {
        if (base.isNegative() || base.isZero() || factor < 1.0 || cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("invalid backoff");
        }
    }

    public static Backoff standard() {
        return new Backoff(Duration.ofSeconds(5), 2.0, Duration.ofMinutes(10));
    }

    public Duration ceiling(int attempt) {
        double millis = base.toMillis() * Math.pow(factor, Math.max(0, attempt - 1));
        double capped = Math.min(millis, cap.toMillis());
        return Duration.ofMillis((long) capped);
    }

    public Duration delay(int attempt, RandomGenerator random) {
        long ceiling = ceiling(attempt).toMillis();
        long half = ceiling / 2;
        long jitter = half == 0 ? 0 : random.nextLong(half + 1);
        return Duration.ofMillis(half + jitter);
    }
}
