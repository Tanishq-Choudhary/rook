package dev.rook.workflow;

import java.time.Duration;

public record RunnerConfig(
        Duration lease, Duration heartbeatEvery, Duration stepTimeout, Backoff backoff) {

    public RunnerConfig {
        if (heartbeatEvery.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("heartbeat interval must be shorter than the lease");
        }
    }

    public static RunnerConfig standard() {
        return new RunnerConfig(
                Duration.ofSeconds(60),
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                Backoff.standard());
    }

    public RunnerConfig withStepTimeout(Duration timeout) {
        return new RunnerConfig(lease, heartbeatEvery, timeout, backoff);
    }

    public RunnerConfig withHeartbeat(Duration interval) {
        return new RunnerConfig(lease, interval, stepTimeout, backoff);
    }

    public RunnerConfig withLease(Duration newLease) {
        return new RunnerConfig(newLease, heartbeatEvery, stepTimeout, backoff);
    }

    public RunnerConfig withBackoff(Backoff newBackoff) {
        return new RunnerConfig(lease, heartbeatEvery, stepTimeout, newBackoff);
    }
}
