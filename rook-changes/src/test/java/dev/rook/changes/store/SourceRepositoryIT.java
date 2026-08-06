package dev.rook.changes.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.changes.source.WatchedSource;
import dev.rook.core.change.SourceKind;
import dev.rook.core.json.Json;
import dev.rook.testkit.TestDatabases;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SourceRepositoryIT {

    private JdbcClient jdbc;
    private SourceRepository repository;

    @BeforeEach
    void setUp() {
        DataSource ds = TestDatabases.fresh();
        jdbc = JdbcClient.create(ds);
        repository = new SourceRepository(jdbc);
    }

    private static WatchedSource source(String id, int interval, boolean enabled) {
        return new WatchedSource(
                id,
                SourceKind.MAVEN,
                id,
                "https://repo.example.test",
                Json.tree(Map.of("groupId", "com.acme", "artifactId", id)),
                null,
                null,
                null,
                null,
                interval,
                enabled);
    }

    @Test
    void registerInsertsAndThenUpdatesConfigurationButKeepsState() {
        repository.register(source("acme", 900, true));
        jdbc.sql("update watched_source set etag = 'e1', last_version = '1.0.0' where id = 'acme'")
                .update();
        repository.register(source("acme", 300, false));
        WatchedSource found = repository.find("acme").orElseThrow();
        assertThat(found.pollIntervalSeconds()).isEqualTo(300);
        assertThat(found.enabled()).isFalse();
        assertThat(found.etag()).isEqualTo("e1");
        assertThat(found.lastVersion()).isEqualTo("1.0.0");
        assertThat(found.configText("groupId")).isEqualTo("com.acme");
    }

    @Test
    void neverCheckedSourcesAreDueAndOnlyOnce() {
        repository.register(source("a", 60, true));
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        assertThat(repository.claimDue(now, 10)).extracting(WatchedSource::id).containsExactly("a");
        assertThat(repository.claimDue(now, 10)).isEmpty();
        assertThat(repository.claimDue(now.plusSeconds(59), 10)).isEmpty();
        assertThat(repository.claimDue(now.plusSeconds(60), 10)).hasSize(1);
    }

    @Test
    void disabledSourcesAreNeverClaimed() {
        repository.register(source("off", 60, false));
        assertThat(repository.claimDue(Instant.parse("2026-08-10T12:00:00Z"), 10)).isEmpty();
        repository.setEnabled("off", true);
        assertThat(repository.claimDue(Instant.parse("2026-08-10T12:00:00Z"), 10)).hasSize(1);
    }

    @Test
    void oldestCheckedSourcesComeFirstAndTheLimitIsRespected() {
        repository.register(source("a", 60, true));
        repository.register(source("b", 60, true));
        repository.register(source("c", 60, true));
        jdbc.sql(
                        "update watched_source set last_checked_at = '2026-08-10T10:00:00Z' where id = 'a'")
                .update();
        jdbc.sql(
                        "update watched_source set last_checked_at = '2026-08-10T09:00:00Z' where id = 'b'")
                .update();
        List<WatchedSource> claimed = repository.claimDue(Instant.parse("2026-08-10T12:00:00Z"), 2);
        assertThat(claimed).extracting(WatchedSource::id).containsExactlyInAnyOrder("c", "b");
    }

    @Test
    void concurrentClaimsNeverHandOutTheSameSourceTwice() throws Exception {
        for (int i = 0; i < 40; i++) {
            repository.register(source("s" + i, 60, true));
        }
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        int workers = 6;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<List<WatchedSource>>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    List<WatchedSource> mine = new ArrayList<>();
                                    for (int round = 0; round < 5; round++) {
                                        mine.addAll(repository.claimDue(now, 4));
                                    }
                                    return mine;
                                }));
            }
            start.countDown();
            Set<String> seen = new HashSet<>();
            int total = 0;
            for (Future<List<WatchedSource>> future : futures) {
                for (WatchedSource claimed : future.get()) {
                    total++;
                    seen.add(claimed.id());
                }
            }
            assertThat(total).isEqualTo(seen.size()).isEqualTo(40);
        }
    }

    @Test
    void listReturnsEverySourceInIdOrder() {
        repository.register(source("b", 60, true));
        repository.register(source("a", 60, false));
        assertThat(repository.list()).extracting(WatchedSource::id).containsExactly("a", "b");
    }
}
