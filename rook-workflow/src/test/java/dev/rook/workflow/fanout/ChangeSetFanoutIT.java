package dev.rook.workflow.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.json.Json;
import dev.rook.workflow.WorkflowFixtures;
import dev.rook.workflow.repo.RepositoryStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChangeSetFanoutIT {

    private WorkflowFixtures fixtures;
    private RepositoryStore store;
    private ChangeSetFanout fanout;

    @BeforeEach
    void setUp() {
        fixtures = WorkflowFixtures.live();
        store = new RepositoryStore(fixtures.jdbc, fixtures.tx);
        fanout =
                new ChangeSetFanout(
                        fixtures.jdbc,
                        fixtures.tx,
                        store,
                        fixtures.jobs,
                        FanoutConfig.standard().withMaxAttempts(7));
    }

    private UUID watcher(String source) {
        UUID repository = fixtures.repository();
        store.watch(repository, source);
        return repository;
    }

    private String payload(UUID changeSetId, String source, boolean migrate, String severity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("changeSetId", changeSetId);
        body.put("sourceId", source);
        body.put("requiresMigration", migrate);
        body.put("highestSeverity", severity);
        return Json.write(body);
    }

    private String payload(UUID changeSetId) {
        return payload(changeSetId, "src", true, "BREAKING");
    }

    private int jobs() {
        return fixtures.count("select count(*) from migration_job");
    }

    @Test
    void everyWatchingRepositoryGetsOneJob() {
        UUID first = watcher("src");
        UUID second = watcher("src");
        UUID changeSet = fixtures.changeSet();
        FanoutResult result = fanout.process("m1", payload(changeSet));
        assertThat(result.outcome()).isEqualTo(FanoutResult.Outcome.JOBS_CREATED);
        assertThat(result.created()).isEqualTo(2);
        assertThat(
                        fixtures.jdbc
                                .sql(
                                        "select repository_id from migration_job where change_set_id = :c")
                                .param("c", changeSet)
                                .query(UUID.class)
                                .list())
                .containsExactlyInAnyOrder(first, second);
        assertThat(fixtures.count("select count(*) from job_event where kind = 'JOB_CREATED'"))
                .isEqualTo(2);
    }

    @Test
    void repositoriesWatchingOtherSourcesAreIgnored() {
        fixtures.source("other");
        watcher("other");
        UUID changeSet = fixtures.changeSet();
        FanoutResult result = fanout.process("m1", payload(changeSet));
        assertThat(result.outcome()).isEqualTo(FanoutResult.Outcome.NO_NEW_JOBS);
        assertThat(jobs()).isZero();
    }

    @Test
    void disabledRepositoriesAndInactiveInstallationsAreSkipped() {
        UUID active = watcher("src");
        UUID disabled = watcher("src");
        fixtures.jdbc
                .sql("update repository set enabled = false where id = :id")
                .param("id", disabled)
                .update();
        UUID orphaned = watcher("src");
        UUID removedInstallation = UUID.randomUUID();
        fixtures.jdbc
                .sql(
                        "insert into installation (id, account_login, status) values (:id, 'gone', 'REMOVED')")
                .param("id", removedInstallation)
                .update();
        fixtures.jdbc
                .sql("update repository set installation_id = :installation where id = :id")
                .param("installation", removedInstallation)
                .param("id", orphaned)
                .update();
        UUID changeSet = fixtures.changeSet();
        fanout.process("m1", payload(changeSet));
        assertThat(
                        fixtures.jdbc
                                .sql("select repository_id from migration_job")
                                .query(UUID.class)
                                .list())
                .containsExactly(active);
    }

    @Test
    void changeSetsThatNeedNoMigrationCreateNoJobs() {
        watcher("src");
        UUID changeSet = fixtures.changeSet("src", false, "ADDITIVE");
        FanoutResult result = fanout.process("m1", payload(changeSet, "src", false, "ADDITIVE"));
        assertThat(result.outcome()).isEqualTo(FanoutResult.Outcome.NOT_ACTIONABLE);
        assertThat(jobs()).isZero();
        assertThat(fixtures.count("select count(*) from processed_message")).isEqualTo(1);
    }

    @Test
    void redeliveredMessagesAreIgnored() {
        watcher("src");
        UUID changeSet = fixtures.changeSet();
        String body = payload(changeSet);
        assertThat(fanout.process("m1", body).outcome())
                .isEqualTo(FanoutResult.Outcome.JOBS_CREATED);
        assertThat(fanout.process("m1", body).outcome()).isEqualTo(FanoutResult.Outcome.DUPLICATE);
        assertThat(jobs()).isEqualTo(1);
    }

    @Test
    void aReplayOnlyAddsJobsThatAreMissing() {
        UUID first = watcher("src");
        UUID changeSet = fixtures.changeSet();
        String body = payload(changeSet);
        fanout.process(changeSet + ":0", body);
        UUID second = watcher("src");
        FanoutResult replay = fanout.process(changeSet + ":1", body);
        assertThat(replay.outcome()).isEqualTo(FanoutResult.Outcome.JOBS_CREATED);
        assertThat(replay.created()).isEqualTo(1);
        assertThat(replay.existing()).isEqualTo(1);
        assertThat(
                        fixtures.jdbc
                                .sql("select repository_id from migration_job")
                                .query(UUID.class)
                                .list())
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    void aReplayWithNothingNewCreatesNothing() {
        watcher("src");
        UUID changeSet = fixtures.changeSet();
        String body = payload(changeSet);
        fanout.process(changeSet + ":0", body);
        FanoutResult replay = fanout.process(changeSet + ":1", body);
        assertThat(replay.outcome()).isEqualTo(FanoutResult.Outcome.NO_NEW_JOBS);
        assertThat(replay.existing()).isEqualTo(1);
        assertThat(jobs()).isEqualTo(1);
    }

    @Test
    void priorityFollowsSeverityAndAttemptsFollowConfiguration() {
        watcher("src");
        fanout.process("m1", payload(fixtures.changeSet("src", true, "BREAKING")));
        UUID deprecation = fixtures.changeSet("src", true, "DEPRECATION");
        fanout.process("m2", payload(deprecation, "src", true, "DEPRECATION"));
        UUID additive = fixtures.changeSet("src", true, "ADDITIVE");
        fanout.process("m3", payload(additive, "src", true, "ADDITIVE"));
        assertThat(
                        fixtures.jdbc
                                .sql("select priority from migration_job order by created_at, id")
                                .query(Integer.class)
                                .list())
                .containsExactlyInAnyOrder(20, 10, 0);
        assertThat(
                        fixtures.jdbc
                                .sql("select distinct max_attempts from migration_job")
                                .query(Integer.class)
                                .single())
                .isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json at all",
                "[]",
                "{}",
                "{\"changeSetId\":\"nope\",\"sourceId\":\"src\",\"requiresMigration\":true,\"highestSeverity\":\"BREAKING\"}",
                "{\"changeSetId\":\"00000000-0000-0000-0000-000000000001\",\"sourceId\":\"src\",\"requiresMigration\":\"yes\",\"highestSeverity\":\"BREAKING\"}",
                "{\"changeSetId\":\"00000000-0000-0000-0000-000000000001\",\"sourceId\":\"src\",\"requiresMigration\":true,\"highestSeverity\":\"HUGE\"}"
            })
    void malformedPayloadsAreParked(String body) {
        watcher("src");
        FanoutResult result = fanout.process("bad-1", body);
        assertThat(result.outcome()).isEqualTo(FanoutResult.Outcome.REJECTED);
        assertThat(fixtures.count("select count(*) from dead_letter where source = 'fanout'"))
                .isEqualTo(1);
        assertThat(jobs()).isZero();
    }

    @Test
    void messagesWithoutAnIdAreParked() {
        watcher("src");
        FanoutResult result = fanout.process(null, payload(fixtures.changeSet()));
        assertThat(result.outcome()).isEqualTo(FanoutResult.Outcome.REJECTED);
        assertThat(fixtures.jdbc.sql("select reason from dead_letter").query(String.class).single())
                .contains("messageId");
        assertThat(jobs()).isZero();
    }

    @Test
    void unknownChangeSetsAreParkedOnceAndNotRetried() {
        watcher("src");
        String body = payload(UUID.randomUUID());
        assertThat(fanout.process("m1", body).outcome()).isEqualTo(FanoutResult.Outcome.REJECTED);
        assertThat(fanout.process("m1", body).outcome()).isEqualTo(FanoutResult.Outcome.DUPLICATE);
        assertThat(fixtures.count("select count(*) from dead_letter where source = 'fanout'"))
                .isEqualTo(1);
    }

    @Test
    void aFailureWhileCreatingJobsRollsTheWholeMessageBack() {
        watcher("src");
        UUID poisoned = watcher("src");
        fixtures.jdbc
                .sql(
                        """
                        create function refuse_job() returns trigger as $$
                        begin
                          if new.repository_id = '%s' then
                            raise exception 'job insert refused';
                          end if;
                          return new;
                        end;
                        $$ language plpgsql
                        """
                                .formatted(poisoned))
                .update();
        fixtures.jdbc
                .sql(
                        "create trigger refuse_job before insert on migration_job for each row execute function refuse_job()")
                .update();
        UUID changeSet = fixtures.changeSet();
        assertThatThrownBy(() -> fanout.process("m1", payload(changeSet)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jobs()).isZero();
        assertThat(fixtures.count("select count(*) from processed_message")).isZero();
        fixtures.jdbc.sql("drop trigger refuse_job on migration_job").update();
        FanoutResult retry = fanout.process("m1", payload(changeSet));
        assertThat(retry.created()).isEqualTo(2);
    }

    @Test
    void concurrentDeliveriesOfOneMessageCreateEachJobOnce() throws Exception {
        for (int i = 0; i < 5; i++) {
            watcher("src");
        }
        UUID changeSet = fixtures.changeSet();
        String body = payload(changeSet);
        CountDownLatch start = new CountDownLatch(1);
        List<FanoutResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<FanoutResult>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return fanout.process("m1", body);
                                }));
            }
            start.countDown();
            for (Future<FanoutResult> future : futures) {
                results.add(future.get());
            }
        }
        assertThat(results)
                .filteredOn(r -> r.outcome() == FanoutResult.Outcome.JOBS_CREATED)
                .hasSize(1);
        assertThat(results)
                .filteredOn(r -> r.outcome() == FanoutResult.Outcome.DUPLICATE)
                .hasSize(7);
        assertThat(jobs()).isEqualTo(5);
    }
}
