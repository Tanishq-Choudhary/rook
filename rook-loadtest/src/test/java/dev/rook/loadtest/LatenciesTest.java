package dev.rook.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatenciesTest {

    @Test
    void anEmptyCollectorSummarisesToZeros() {
        Latencies.Summary summary = new Latencies().summary();

        assertThat(summary.count()).isZero();
        assertThat(summary.p99Millis()).isZero();
        assertThat(summary.maxMillis()).isZero();
    }

    @Test
    void percentilesOfOneToAHundredMillisecondsAreTheirRanks() {
        Latencies latencies = new Latencies();
        for (int i = 100; i >= 1; i--) {
            latencies.add(i * 1_000_000L);
        }

        Latencies.Summary summary = latencies.summary();

        assertThat(summary.count()).isEqualTo(100);
        assertThat(summary.minMillis()).isEqualTo(1.0);
        assertThat(summary.p50Millis()).isEqualTo(50.0);
        assertThat(summary.p90Millis()).isEqualTo(90.0);
        assertThat(summary.p95Millis()).isEqualTo(95.0);
        assertThat(summary.p99Millis()).isEqualTo(99.0);
        assertThat(summary.maxMillis()).isEqualTo(100.0);
        assertThat(summary.meanMillis()).isEqualTo(50.5);
    }

    @Test
    void aSingleSampleIsEveryPercentile() {
        Latencies latencies = new Latencies();
        latencies.add(7_000_000L);

        Latencies.Summary summary = latencies.summary();

        assertThat(summary.p50Millis()).isEqualTo(7.0);
        assertThat(summary.p99Millis()).isEqualTo(7.0);
        assertThat(summary.minMillis()).isEqualTo(summary.maxMillis());
    }

    @Test
    void growsPastItsInitialCapacity() {
        Latencies latencies = new Latencies();
        for (int i = 0; i < 5_000; i++) {
            latencies.add(1_000_000L);
        }

        assertThat(latencies.count()).isEqualTo(5_000);
        assertThat(latencies.summary().p50Millis()).isEqualTo(1.0);
    }

    @Test
    void percentileRankNeverLeavesTheArray() {
        long[] sorted = {1, 2, 3};

        assertThat(Latencies.percentile(sorted, 0.0)).isEqualTo(1);
        assertThat(Latencies.percentile(sorted, 1.0)).isEqualTo(3);
        assertThat(Latencies.percentile(sorted, 2.0)).isEqualTo(3);
    }
}
