package dev.rook.pipeline.step;

import dev.rook.agent.strategy.Strategy;
import java.util.UUID;

public interface StrategyProvider {

    final class Prepared implements AutoCloseable {

        private final Strategy strategy;
        private final long reserveMicros;
        private final Runnable cleanup;

        public Prepared(Strategy strategy, long reserveMicros, Runnable cleanup) {
            this.strategy = strategy;
            this.reserveMicros = reserveMicros;
            this.cleanup = cleanup;
        }

        public static Prepared free(Strategy strategy) {
            return new Prepared(strategy, 0, () -> {});
        }

        public Strategy strategy() {
            return strategy;
        }

        public long reserveMicros() {
            return reserveMicros;
        }

        @Override
        public void close() {
            cleanup.run();
        }
    }

    Prepared prepare(UUID jobId);
}
