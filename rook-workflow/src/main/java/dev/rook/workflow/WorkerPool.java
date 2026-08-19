package dev.rook.workflow;

import dev.rook.workflow.RunResult.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorkerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final JobRunner runner;
    private final String prefix;
    private final Duration idleSleep;
    private final Map<String, Worker> workers = new LinkedHashMap<>();
    private final AtomicInteger processed = new AtomicInteger();
    private volatile boolean running;

    public WorkerPool(JobRunner runner, String prefix, int size, Duration idleSleep) {
        this.runner = runner;
        this.prefix = prefix;
        this.idleSleep = idleSleep;
        for (int i = 0; i < size; i++) {
            String id = prefix + "-" + i;
            workers.put(id, new Worker(id));
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workers.values().forEach(Worker::start);
    }

    public synchronized void crash(String workerId) {
        Worker worker = workers.get(workerId);
        if (worker != null) {
            worker.crash.crash();
            log.warn("worker {} crashed on purpose", workerId);
        }
    }

    public synchronized List<String> alive() {
        List<String> alive = new ArrayList<>();
        workers.forEach(
                (id, worker) -> {
                    if (worker.thread != null
                            && worker.thread.isAlive()
                            && !worker.crash.crashed()) {
                        alive.add(id);
                    }
                });
        return alive;
    }

    public List<String> workerIds() {
        return List.copyOf(workers.keySet());
    }

    public int processed() {
        return processed.get();
    }

    public synchronized void stop(Duration grace) {
        running = false;
        long deadline = System.nanoTime() + grace.toNanos();
        for (Worker worker : workers.values()) {
            if (worker.thread == null) {
                continue;
            }
            try {
                long remaining = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
                worker.thread.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (worker.thread.isAlive()) {
                worker.thread.interrupt();
                try {
                    worker.thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(5));
    }

    private final class Worker implements Runnable {

        private final String id;
        private final CrashSignal.Switch crash = CrashSignal.switchable();
        private Thread thread;

        Worker(String id) {
            this.id = id;
        }

        void start() {
            thread = Thread.ofVirtual().name(prefix + "-" + id).unstarted(this);
            thread.start();
        }

        @Override
        public void run() {
            while (running && !crash.crashed() && !Thread.currentThread().isInterrupted()) {
                RunResult result;
                try {
                    result = runner.runOnce(id, crash);
                } catch (RuntimeException e) {
                    log.error("worker {} iteration failed", id, e);
                    sleep(idleSleep);
                    continue;
                }
                if (result.outcome() == Outcome.CRASHED) {
                    return;
                }
                if (result.idleRun()) {
                    sleep(idleSleep);
                } else {
                    processed.incrementAndGet();
                }
            }
        }

        private void sleep(Duration base) {
            long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base.toMillis() / 2));
            try {
                Thread.sleep(base.toMillis() + jitter);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
