package dev.rook.workflow;

import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.core.job.JobState;
import dev.rook.workflow.RunResult.Outcome;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobRepository jobs;
    private final Map<JobState, StepHandler> handlers = new EnumMap<>(JobState.class);
    private final RunnerConfig config;
    private final WorkflowMetrics metrics;
    private final RandomGenerator random;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public JobRunner(
            JobRepository jobs,
            List<StepHandler> stepHandlers,
            RunnerConfig config,
            WorkflowMetrics metrics,
            RandomGenerator random) {
        this.jobs = jobs;
        this.config = config;
        this.metrics = metrics;
        this.random = random;
        for (StepHandler handler : stepHandlers) {
            if (handlers.put(handler.state(), handler) != null) {
                throw new IllegalArgumentException("two handlers for " + handler.state());
            }
        }
    }

    public RunResult runOnce(String workerId) {
        return runOnce(workerId, CrashSignal.NEVER);
    }

    public RunResult runOnce(String workerId, CrashSignal crash) {
        Optional<JobRepository.Claim> claimed = jobs.claim(workerId, config.lease());
        if (claimed.isEmpty()) {
            return RunResult.idle();
        }
        Job job = claimed.get().job();
        metrics.claimed(claimed.get().reclaimed());
        if (job.attempt() >= job.maxAttempts()) {
            return give(
                    job,
                    "gave up after " + job.attempt() + " attempts without finishing a step",
                    Duration.ZERO);
        }
        StepHandler handler = handlers.get(job.state());
        if (handler == null) {
            return failed(
                    job, new StepFailure.Retryable("no handler for " + job.state()), Duration.ZERO);
        }
        JobContext context = new JobContext(job, workerId, jobs, config.lease());
        jobs.recordEvent(
                job.id(),
                "STEP_STARTED",
                job.state(),
                Map.of("worker", workerId, "attempt", job.attempt()));
        long started = System.nanoTime();
        Future<StepResult> future = executor.submit(() -> handler.execute(context));
        try {
            StepResult result = await(future, context, job, crash);
            if (crash.crashed()) {
                throw new Crashed();
            }
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            return apply(job, result, took);
        } catch (Crashed e) {
            future.cancel(true);
            return new RunResult(Outcome.CRASHED, job.id(), job.state());
        } catch (LeaseLostException e) {
            return leaseLost(job, Duration.ofNanos(System.nanoTime() - started));
        } catch (TimeoutException e) {
            return failed(
                    job,
                    new StepFailure.Retryable("step exceeded " + config.stepTimeout()),
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (ExecutionException e) {
            return failed(job, e.getCause(), Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException e) {
            future.cancel(true);
            jobs.release(job.id(), job.fence());
            Thread.currentThread().interrupt();
            return new RunResult(Outcome.RELEASED, job.id(), job.state());
        }
    }

    private StepResult await(
            Future<StepResult> future, JobContext context, Job job, CrashSignal crash)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + config.stepTimeout().toNanos();
        while (true) {
            if (crash.crashed()) {
                throw new Crashed();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                future.cancel(true);
                throw new TimeoutException();
            }
            long wait = Math.min(config.heartbeatEvery().toNanos(), remaining);
            try {
                return future.get(wait, TimeUnit.NANOSECONDS);
            } catch (TimeoutException tick) {
                if (crash.crashed()) {
                    throw new Crashed();
                }
                if (!jobs.heartbeat(job.id(), job.fence(), config.lease())) {
                    context.markLeaseLost();
                    future.cancel(true);
                    throw new LeaseLostException(job.id());
                }
            }
        }
    }

    private RunResult apply(Job job, StepResult result, Duration took) {
        switch (result) {
            case StepResult.Advance advance -> {
                boolean applied =
                        jobs.advance(
                                job.id(),
                                job.fence(),
                                job.state(),
                                advance.next(),
                                advance.checkpoint(),
                                Map.of("durationMillis", took.toMillis()));
                if (!applied) {
                    return leaseLost(job, took);
                }
                metrics.step(job.state(), "advanced", took);
                return new RunResult(Outcome.ADVANCED, job.id(), advance.next());
            }
            case StepResult.Finish finish -> {
                boolean applied =
                        jobs.finish(
                                job.id(),
                                job.fence(),
                                job.state(),
                                finish.terminal(),
                                finish.reason());
                if (!applied) {
                    return leaseLost(job, took);
                }
                metrics.step(job.state(), finish.terminal().name().toLowerCase(), took);
                return new RunResult(Outcome.FINISHED, job.id(), finish.terminal());
            }
            case StepResult.Retry retry -> {
                return retryOrGiveUp(job, retry.reason(), retry.delay(), took);
            }
        }
    }

    private RunResult failed(Job job, Throwable error, Duration took) {
        if (error instanceof LeaseLostException) {
            return leaseLost(job, took);
        }
        String reason = describe(error);
        log.warn("job {} step {} failed: {}", job.id(), job.state(), reason);
        jobs.recordEvent(
                job.id(),
                "STEP_FAILED",
                job.state(),
                Map.of("error", reason, "class", error.getClass().getSimpleName()));
        if (error instanceof StepFailure.Fatal) {
            boolean applied =
                    jobs.finish(job.id(), job.fence(), job.state(), JobState.FAILED, reason);
            if (!applied) {
                return leaseLost(job, took);
            }
            metrics.step(job.state(), "failed", took);
            return new RunResult(Outcome.FAILED, job.id(), JobState.FAILED);
        }
        Duration delay =
                error instanceof StepFailure.Retryable retryable
                                && retryable.suggestedDelay() != null
                        ? retryable.suggestedDelay()
                        : null;
        return retryOrGiveUp(job, reason, delay, took);
    }

    private RunResult retryOrGiveUp(
            Job job, String reason, Duration requestedDelay, Duration took) {
        int next = job.attempt() + 1;
        if (next >= job.maxAttempts()) {
            return give(job, reason, took);
        }
        Duration delay =
                requestedDelay != null ? requestedDelay : config.backoff().delay(next, random);
        int attempt = jobs.retry(job.id(), job.fence(), reason, delay);
        if (attempt < 0) {
            return leaseLost(job, took);
        }
        metrics.step(job.state(), "retry", took);
        return new RunResult(Outcome.RETRY_SCHEDULED, job.id(), job.state());
    }

    private RunResult give(Job job, String reason, Duration took) {
        boolean applied = jobs.finish(job.id(), job.fence(), job.state(), JobState.DEAD, reason);
        if (!applied) {
            return leaseLost(job, took);
        }
        metrics.step(job.state(), "dead", took);
        return new RunResult(Outcome.DEAD, job.id(), JobState.DEAD);
    }

    private RunResult leaseLost(Job job, Duration took) {
        log.warn("job {} lost its lease during {}", job.id(), job.state());
        metrics.leaseLost(job.state());
        metrics.step(job.state(), "lease_lost", took);
        return new RunResult(Outcome.LEASE_LOST, job.id(), job.state());
    }

    private static String describe(Throwable error) {
        if (error instanceof RookException rook && rook.code() == ErrorCode.UNAVAILABLE) {
            return "unavailable: " + rook.getMessage();
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class Crashed extends RuntimeException {

        Crashed() {
            super("worker crashed", null, false, false);
        }
    }
}
