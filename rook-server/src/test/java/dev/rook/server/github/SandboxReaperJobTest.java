package dev.rook.server.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SandboxReaperJobTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void aSweepPassesTheAgeAndCountsWhatWasRemoved() {
        List<Duration> asked = new ArrayList<>();
        SandboxReaperJob job =
                new SandboxReaperJob(
                        age -> {
                            asked.add(age);
                            return 3;
                        },
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(30),
                        meters);

        assertThat(job.sweep()).isEqualTo(3);
        assertThat(job.sweep()).isEqualTo(3);

        assertThat(asked).containsExactly(Duration.ofMinutes(30), Duration.ofMinutes(30));
        assertThat(meters.get("rook.sandbox.reaped").counter().count()).isEqualTo(6.0);
    }

    @Test
    void aSweepThatFindsNothingCountsNothing() {
        SandboxReaperJob job =
                new SandboxReaperJob(
                        age -> 0, Duration.ofMinutes(5), Duration.ofMinutes(30), meters);

        assertThat(job.sweep()).isZero();
        assertThat(meters.find("rook.sandbox.reaped").counter()).isNull();
    }

    @Test
    void aFailingSweepIsCountedAndDoesNotEscape() {
        SandboxReaperJob job =
                new SandboxReaperJob(
                        age -> {
                            throw new IllegalStateException("docker is down");
                        },
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(30),
                        meters);

        assertThat(job.sweep()).isZero();
        assertThat(meters.get("rook.sandbox.reap.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void theScheduleKeepsSweepingUntilItIsStopped() {
        AtomicInteger sweeps = new AtomicInteger();
        List<Integer> seen = new CopyOnWriteArrayList<>();
        SandboxReaperJob job =
                new SandboxReaperJob(
                        age -> {
                            seen.add(sweeps.incrementAndGet());
                            return 0;
                        },
                        Duration.ofMillis(20),
                        Duration.ofMinutes(1),
                        meters);

        assertThat(job.isRunning()).isFalse();
        job.start();
        job.start();
        assertThat(job.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> sweeps.get() >= 3);
        job.stop();

        assertThat(job.isRunning()).isFalse();
        int stoppedAt = sweeps.get();
        await().pollDelay(Duration.ofMillis(150)).atMost(Duration.ofSeconds(2)).until(() -> true);
        assertThat(sweeps.get()).isLessThanOrEqualTo(stoppedAt + 1);
    }

    @Test
    void rejectsAnIntervalThatCouldNotRun() {
        assertThatThrownBy(
                        () ->
                                new SandboxReaperJob(
                                        age -> 0, Duration.ZERO, Duration.ofMinutes(1), meters))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePipelineSettingsDefaultToAFiveMinuteSweepOfThirtyMinuteOldContainers() {
        PipelineProperties properties =
                new PipelineProperties(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null);

        assertThat(properties.reapEvery()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.reapAfter()).isEqualTo(Duration.ofMinutes(30));
        assertThatThrownBy(
                        () ->
                                new PipelineProperties(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Duration.ZERO,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
