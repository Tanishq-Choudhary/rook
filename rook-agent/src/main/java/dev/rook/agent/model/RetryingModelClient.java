package dev.rook.agent.model;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RetryingModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingModelClient.class);

    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final ModelClient delegate;
    private final int maxAttempts;
    private final Duration base;
    private final Duration cap;
    private final Sleeper sleeper;
    private final RandomGenerator random;

    public RetryingModelClient(
            ModelClient delegate,
            int maxAttempts,
            Duration base,
            Duration cap,
            Sleeper sleeper,
            RandomGenerator random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.base = base;
        this.cap = cap;
        this.sleeper = sleeper;
        this.random = random;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        ModelException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return delegate.complete(request);
            } catch (ModelException e) {
                if (!e.retryable()) {
                    throw e;
                }
                last = e;
                if (attempt == maxAttempts) {
                    break;
                }
                Duration delay = delay(attempt);
                log.warn(
                        "model call failed (attempt {} of {}), retrying in {} ms: {}",
                        attempt,
                        maxAttempts,
                        delay.toMillis(),
                        e.getMessage());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ModelException("interrupted while waiting to retry", false, e);
                }
            }
        }
        throw new ModelException(
                "model call failed after " + maxAttempts + " attempts: " + last.getMessage(),
                false,
                last);
    }

    Duration delay(int attempt) {
        double millis = base.toMillis() * Math.pow(2, attempt - 1);
        long ceiling = (long) Math.min(millis, cap.toMillis());
        long half = ceiling / 2;
        long jitter = half == 0 ? 0 : random.nextLong(half + 1);
        return Duration.ofMillis(half + jitter);
    }
}
