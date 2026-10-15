package dev.rook.loadtest;

import java.util.Arrays;

public final class Latencies {

    public record Summary(
            long count,
            double minMillis,
            double meanMillis,
            double p50Millis,
            double p90Millis,
            double p95Millis,
            double p99Millis,
            double maxMillis) {}

    private long[] samples = new long[1024];
    private int size;

    public synchronized void add(long nanos) {
        if (size == samples.length) {
            samples = Arrays.copyOf(samples, size * 2);
        }
        samples[size++] = nanos;
    }

    public synchronized int count() {
        return size;
    }

    public synchronized Summary summary() {
        if (size == 0) {
            return new Summary(0, 0, 0, 0, 0, 0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        double total = 0;
        for (long value : sorted) {
            total += value;
        }
        return new Summary(
                size,
                millis(sorted[0]),
                millis(total / size),
                millis(percentile(sorted, 0.50)),
                millis(percentile(sorted, 0.90)),
                millis(percentile(sorted, 0.95)),
                millis(percentile(sorted, 0.99)),
                millis(sorted[size - 1]));
    }

    static long percentile(long[] sorted, double fraction) {
        int rank = (int) Math.ceil(fraction * sorted.length);
        return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
    }

    private static double millis(double nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }
}
