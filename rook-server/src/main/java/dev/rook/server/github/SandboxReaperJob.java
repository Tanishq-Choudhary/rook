package dev.rook.server.github;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class SandboxReaperJob implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SandboxReaperJob.class);

    private final ToIntFunction<Duration> reaper;
    private final Duration every;
    private final Duration olderThan;
    private final MeterRegistry meters;
    private ScheduledExecutorService executor;

    public SandboxReaperJob(
            ToIntFunction<Duration> reaper,
            Duration every,
            Duration olderThan,
            MeterRegistry meters) {
        if (every.isNegative() || every.isZero() || olderThan.isNegative()) {
            throw new IllegalArgumentException("the interval must be positive");
        }
        this.reaper = reaper;
        this.every = every;
        this.olderThan = olderThan;
        this.meters = meters;
    }

    public int sweep() {
        try {
            int reaped = reaper.applyAsInt(olderThan);
            if (reaped > 0) {
                meters.counter("rook.sandbox.reaped").increment(reaped);
                log.warn("removed {} sandbox containers that outlived their run", reaped);
            }
            return reaped;
        } catch (RuntimeException e) {
            meters.counter("rook.sandbox.reap.failures").increment();
            log.error("sandbox sweep failed", e);
            return 0;
        }
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        task ->
                                Thread.ofPlatform()
                                        .daemon()
                                        .name("sandbox-reaper")
                                        .unstarted(task));
        executor.scheduleWithFixedDelay(
                this::sweep, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
