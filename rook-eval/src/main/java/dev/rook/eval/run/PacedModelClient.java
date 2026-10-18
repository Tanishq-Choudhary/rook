package dev.rook.eval.run;

import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.ModelRequest;
import dev.rook.agent.model.ModelResponse;
import java.time.Duration;
import java.util.function.LongSupplier;

public final class PacedModelClient implements ModelClient {

    public interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    private final ModelClient delegate;
    private final long gapNanos;
    private final LongSupplier nanos;
    private final Pause pause;
    private long nextAllowed;

    public PacedModelClient(ModelClient delegate, Duration gap) {
        this(delegate, gap, System::nanoTime, Thread::sleep);
    }

    PacedModelClient(ModelClient delegate, Duration gap, LongSupplier nanos, Pause pause) {
        this.delegate = delegate;
        this.gapNanos = gap.toNanos();
        this.nanos = nanos;
        this.pause = pause;
        this.nextAllowed = nanos.getAsLong();
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        awaitTurn();
        return delegate.complete(request);
    }

    private synchronized void awaitTurn() {
        long now = nanos.getAsLong();
        long remaining = nextAllowed - now;
        if (remaining > 0) {
            try {
                pause.sleep(Math.max(1, remaining / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now = nextAllowed;
        }
        nextAllowed = now + gapNanos;
    }
}
