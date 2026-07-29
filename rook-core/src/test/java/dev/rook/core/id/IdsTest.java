package dev.rook.core.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersionSevenIdsWithRfcVariant() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheClockTimestamp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-08-01T10:15:30.123Z"), ZoneOffset.UTC);
        UUID id = Ids.generator(fixed).next();
        assertThat(Ids.timestampMillis(id)).isEqualTo(fixed.millis());
    }

    @Test
    void idsFromAFrozenClockStayStrictlyIncreasing() {
        Clock fixed = Clock.fixed(Instant.parse("2026-08-01T10:15:30Z"), ZoneOffset.UTC);
        Ids.UuidV7Generator generator = Ids.generator(fixed);
        String previous = generator.next().toString();
        for (int i = 0; i < 20_000; i++) {
            String next = generator.next().toString();
            assertThat(next).isGreaterThan(previous);
            previous = next;
        }
    }

    @Test
    void idsFromTheSystemClockSortInCreationOrder() {
        List<String> ids =
                IntStream.range(0, 50_000).mapToObj(i -> Ids.newId().toString()).toList();
        List<String> sorted = ids.stream().sorted().toList();
        assertThat(ids).isEqualTo(sorted);
    }

    @Test
    void concurrentGenerationNeverCollides() throws Exception {
        int threads = 8;
        int perThread = 10_000;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<List<UUID>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> generate(perThread)));
            }
            Set<UUID> all = new HashSet<>();
            for (Future<List<UUID>> f : futures) {
                all.addAll(f.get());
            }
            assertThat(all).hasSize(threads * perThread);
        }
    }

    private static List<UUID> generate(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(Ids.newId());
        }
        return ids;
    }

    @Test
    void sequenceOverflowAdvancesTheTimestampInsteadOfWrapping() {
        Clock fixed = Clock.fixed(Instant.parse("2026-08-01T10:15:30Z"), ZoneOffset.UTC);
        Ids.UuidV7Generator generator = Ids.generator(fixed);
        UUID first = generator.next();
        UUID last = first;
        for (int i = 0; i < 10_000; i++) {
            last = generator.next();
        }
        assertThat(Ids.timestampMillis(last)).isGreaterThan(Ids.timestampMillis(first));
    }
}
