package dev.rook.loadtest;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class LoadRunner {

    public record Result(
            String scenario,
            int concurrency,
            double seconds,
            long requests,
            long failures,
            double requestsPerSecond,
            Map<String, Long> statuses,
            Latencies.Summary latency) {}

    private final HttpClient client;

    public LoadRunner() {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .build();
    }

    public Result run(Scenario scenario, int concurrency, Duration warmup, Duration duration)
            throws Exception {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        AtomicLong sequence = new AtomicLong();
        drive(scenario, concurrency, warmup, sequence, new Latencies(), new ConcurrentHashMap<>());
        Latencies latencies = new Latencies();
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        long started = System.nanoTime();
        drive(scenario, concurrency, duration, sequence, latencies, statuses);
        double seconds = (System.nanoTime() - started) / 1e9;
        long failures = 0;
        Map<String, Long> counted = new TreeMap<>();
        for (Map.Entry<String, LongAdder> entry : statuses.entrySet()) {
            counted.put(entry.getKey(), entry.getValue().sum());
            if (!entry.getKey().startsWith("ok-")) {
                failures += entry.getValue().sum();
            }
        }
        long requests = latencies.count();
        return new Result(
                scenario.name(),
                concurrency,
                Math.round(seconds * 100) / 100.0,
                requests,
                failures,
                Math.round(requests / seconds * 10) / 10.0,
                counted,
                latencies.summary());
    }

    private void drive(
            Scenario scenario,
            int concurrency,
            Duration duration,
            AtomicLong sequence,
            Latencies latencies,
            Map<String, LongAdder> statuses)
            throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                workers.add(
                        pool.submit(
                                () -> {
                                    while (System.nanoTime() < deadline) {
                                        one(
                                                scenario,
                                                sequence.incrementAndGet(),
                                                latencies,
                                                statuses);
                                    }
                                }));
            }
            for (Future<?> worker : workers) {
                worker.get();
            }
        }
    }

    private void one(
            Scenario scenario, long number, Latencies latencies, Map<String, LongAdder> statuses) {
        long start = System.nanoTime();
        String label;
        try {
            HttpResponse<Void> response =
                    client.send(scenario.request(number), HttpResponse.BodyHandlers.discarding());
            label =
                    (scenario.accepted(response.statusCode()) ? "ok-" : "bad-")
                            + response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            label = "error-" + e.getClass().getSimpleName();
        }
        latencies.add(System.nanoTime() - start);
        statuses.computeIfAbsent(label, key -> new LongAdder()).increment();
    }
}
