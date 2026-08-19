package dev.rook.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.job.JobState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobInvariantFuzzIT {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private record Held(UUID id, long fence, JobState state) {}

    @ParameterizedTest
    @ValueSource(longs = {1L, 7L, 42L, 2026L, 987654321L})
    void randomInterleavingsPreserveTheSchedulingInvariants(long seed) {
        WorkflowFixtures fixtures = new WorkflowFixtures();
        JobRepository jobs = fixtures.jobs;
        Random random = new Random(seed);
        List<UUID> repositories = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            repositories.add(fixtures.repository());
        }
        List<Held> held = new ArrayList<>();
        Map<UUID, Long> lastFence = new HashMap<>();
        Set<UUID> terminal = new HashSet<>();
        Map<UUID, JobState> terminalState = new HashMap<>();

        for (int step = 0; step < 220; step++) {
            int choice = random.nextInt(100);
            if (choice < 14) {
                fixtures.job(
                        repositories.get(random.nextInt(repositories.size())), random.nextInt(3));
            } else if (choice < 38) {
                String worker = "w" + random.nextInt(5);
                jobs.claim(worker, LEASE)
                        .ifPresent(
                                c ->
                                        held.add(
                                                new Held(
                                                        c.job().id(),
                                                        c.job().fence(),
                                                        c.job().state())));
            } else if (choice < 58 && !held.isEmpty()) {
                Held h = held.remove(random.nextInt(held.size()));
                advance(jobs, h, random);
            } else if (choice < 66 && !held.isEmpty()) {
                Held h = held.get(random.nextInt(held.size()));
                jobs.heartbeat(h.id(), h.fence(), LEASE);
            } else if (choice < 74 && !held.isEmpty()) {
                Held h = held.remove(random.nextInt(held.size()));
                jobs.retry(h.id(), h.fence(), "fuzz", Duration.ofSeconds(random.nextInt(40)));
            } else if (choice < 79 && !held.isEmpty()) {
                Held h = held.remove(random.nextInt(held.size()));
                jobs.release(h.id(), h.fence());
            } else if (choice < 84) {
                List<UUID> waiting =
                        fixtures.jdbc
                                .sql(
                                        "select id from migration_job where state = 'AWAITING_APPROVAL'")
                                .query(UUID.class)
                                .list();
                if (!waiting.isEmpty()) {
                    jobs.recordApproval(
                            waiting.get(random.nextInt(waiting.size())),
                            random.nextBoolean(),
                            "fuzz");
                }
            } else {
                fixtures.testClock().advance(Duration.ofSeconds(random.nextInt(90)));
            }
            check(fixtures, lastFence, terminal, terminalState);
        }
        assertThat(fixtures.count("select count(*) from job_event")).isPositive();
    }

    private static void advance(JobRepository jobs, Held h, Random random) {
        Job current = jobs.find(h.id()).orElseThrow();
        JobState from = current.state();
        List<JobState> options = new ArrayList<>(from.successors());
        if (options.isEmpty()) {
            return;
        }
        options.sort(null);
        JobState next = options.get(random.nextInt(options.size()));
        if (next.isTerminal()) {
            jobs.finish(h.id(), h.fence(), from, next, "fuzz");
        } else {
            jobs.advance(h.id(), h.fence(), from, next, Map.of("step", from.name()), Map.of());
        }
    }

    private static void check(
            WorkflowFixtures fixtures,
            Map<UUID, Long> lastFence,
            Set<UUID> terminal,
            Map<UUID, JobState> terminalState) {
        Integer maxRunning =
                fixtures.jdbc
                        .sql(
                                """
                                select coalesce(max(c), 0) from (
                                  select count(*) as c from migration_job
                                  where state in ('INDEXING','PROPOSING','VERIFYING','PUBLISHING')
                                  group by repository_id) t
                                """)
                        .query(Integer.class)
                        .single();
        assertThat(maxRunning).as("running jobs per repository").isLessThanOrEqualTo(1);
        Integer inconsistentLeases =
                fixtures.jdbc
                        .sql(
                                "select count(*) from migration_job where (lease_owner is null) <> (lease_expires_at is null)")
                        .query(Integer.class)
                        .single();
        assertThat(inconsistentLeases).isZero();
        Integer leasedTerminal =
                fixtures.jdbc
                        .sql(
                                "select count(*) from migration_job where state in ('COMPLETED','NO_IMPACT','REJECTED','FAILED','DEAD','SUPERSEDED','CANCELLED') and lease_owner is not null")
                        .query(Integer.class)
                        .single();
        assertThat(leasedTerminal).as("terminal jobs holding a lease").isZero();
        fixtures.jdbc
                .sql("select id, fence, state from migration_job")
                .query(
                        (rs, n) -> {
                            UUID id = rs.getObject("id", UUID.class);
                            long fence = rs.getLong("fence");
                            JobState state = JobState.valueOf(rs.getString("state"));
                            Long previous = lastFence.put(id, fence);
                            assertThat(previous == null || previous <= fence)
                                    .as("fence never decreases")
                                    .isTrue();
                            if (terminal.contains(id)) {
                                assertThat(state)
                                        .as("terminal state is final")
                                        .isEqualTo(terminalState.get(id));
                            } else if (state.isTerminal()) {
                                terminal.add(id);
                                terminalState.put(id, state);
                            }
                            return null;
                        })
                .list();
        List<Map<String, Object>> transitions =
                fixtures.jdbc
                        .sql(
                                "select data ->> 'from' as f, data ->> 'to' as t from job_event where kind = 'STATE_CHANGED' and data ->> 'from' is not null")
                        .query()
                        .listOfRows();
        for (Map<String, Object> row : transitions) {
            JobState from = JobState.valueOf((String) row.get("f"));
            JobState to = JobState.valueOf((String) row.get("t"));
            assertThat(from.canTransitionTo(to)).as(from + " to " + to).isTrue();
        }
    }
}
