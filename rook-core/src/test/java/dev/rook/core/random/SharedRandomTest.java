package dev.rook.core.random;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SharedRandomTest {

    @Test
    void isOneSharedInstance() {
        assertThat(SharedRandom.get()).isSameAs(SharedRandom.get());
    }

    @Test
    void doublesStayInTheUnitInterval() {
        for (int i = 0; i < 10_000; i++) {
            double value = SharedRandom.get().nextDouble();
            assertThat(value).isBetween(0.0, 1.0).isLessThan(1.0);
        }
    }

    @Test
    void boundedValuesRespectTheirBounds() {
        for (int i = 0; i < 10_000; i++) {
            assertThat(SharedRandom.get().nextInt(7)).isBetween(0, 6);
            assertThat(SharedRandom.get().nextLong(5, 9)).isBetween(5L, 8L);
        }
    }

    @Test
    void manyThreadsGetDifferentStreamsWithoutFailing() throws Exception {
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    for (int i = 0; i < 5_000; i++) {
                                        seen.add(SharedRandom.get().nextLong());
                                    }
                                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        assertThat(seen.size()).isGreaterThan(39_000);
    }
}
