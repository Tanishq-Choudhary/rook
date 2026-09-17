package dev.rook.control.limit;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RateLimiter {

    public record Result(boolean allowed, int remaining, long retryAfterMillis) {}

    private static final class Bucket {
        double tokens;
        Instant refilledAt;

        Bucket(double tokens, Instant refilledAt) {
            this.tokens = tokens;
            this.refilledAt = refilledAt;
        }
    }

    private final Clock clock;
    private final int capacity;
    private final double refillPerSecond;
    private final Map<String, Bucket> buckets;

    public RateLimiter(Clock clock, int capacity, double refillPerSecond, int maxKeys) {
        if (capacity < 1 || refillPerSecond <= 0 || maxKeys < 1) {
            throw new IllegalArgumentException(
                    "capacity, refill rate and key limit must be positive");
        }
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.buckets =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                        return size() > maxKeys;
                    }
                };
    }

    public synchronized Result tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    public synchronized Result tryAcquire(String key, int cost) {
        if (cost < 1 || cost > capacity) {
            throw new IllegalArgumentException("cost must be between 1 and the capacity");
        }
        Instant now = clock.instant();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        double elapsedSeconds =
                Math.max(0, (now.toEpochMilli() - bucket.refilledAt.toEpochMilli()) / 1000.0);
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillPerSecond);
        bucket.refilledAt = now;
        if (bucket.tokens >= cost) {
            bucket.tokens -= cost;
            return new Result(true, (int) Math.floor(bucket.tokens), 0);
        }
        double missing = cost - bucket.tokens;
        long retryAfter = (long) Math.ceil(missing / refillPerSecond * 1000.0);
        return new Result(false, (int) Math.floor(bucket.tokens), retryAfter);
    }

    public synchronized int trackedKeys() {
        return buckets.size();
    }
}
