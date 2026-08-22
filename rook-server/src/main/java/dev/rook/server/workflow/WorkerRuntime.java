package dev.rook.server.workflow;

import dev.rook.workflow.Backoff;
import dev.rook.workflow.JobRepository;
import dev.rook.workflow.JobRunner;
import dev.rook.workflow.RunnerConfig;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.WorkerPool;
import dev.rook.workflow.WorkflowMetrics;
import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkerRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntime.class);

    private final JobRunner runner;
    private final WorkerPool pool;
    private final int expected;

    public WorkerRuntime(
            JobRepository jobs,
            List<StepHandler> handlers,
            WorkflowProperties properties,
            WorkflowMetrics metrics) {
        if (handlers.isEmpty()) {
            log.warn("no step handlers are registered, jobs will wait until a worker has some");
            this.runner = null;
            this.pool = null;
            this.expected = 0;
            return;
        }
        RunnerConfig config =
                new RunnerConfig(
                        properties.lease(),
                        properties.heartbeatEvery(),
                        properties.stepTimeout(),
                        Backoff.standard());
        this.runner =
                new JobRunner(
                        jobs, handlers, config, metrics, RandomGenerator.of("L64X128MixRandom"));
        this.pool = new WorkerPool(runner, "worker", properties.workers(), properties.idleSleep());
        this.expected = properties.workers();
    }

    public boolean enabled() {
        return pool != null;
    }

    public int expected() {
        return expected;
    }

    public int alive() {
        return pool == null ? 0 : pool.alive().size();
    }

    void start() {
        if (pool != null) {
            pool.start();
        }
    }

    void stop(Duration grace) {
        if (pool != null) {
            pool.stop(grace);
        }
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
        if (runner != null) {
            runner.close();
        }
    }
}
