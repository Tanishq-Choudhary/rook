package dev.rook.control.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.control.store.ControlFixtures;
import dev.rook.core.json.Json;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class AuditLogIT {

    private ControlFixtures fixtures;
    private AuditLog log;

    @BeforeEach
    void setUp() {
        fixtures = new ControlFixtures();
        log = new AuditLog(fixtures.jdbc, fixtures.tx, fixtures.clock);
    }

    private void fill(int count) {
        for (int i = 0; i < count; i++) {
            fixtures.clock.advance(Duration.ofSeconds(7));
            log.append(
                    "worker-" + (i % 3),
                    "STEP_DONE",
                    "job-" + (i % 2),
                    Map.of("step", i, "ok", true));
        }
    }

    private void tamper(String sql) {
        fixtures.jdbc.sql("alter table audit_log disable trigger audit_log_no_change").update();
        try {
            fixtures.jdbc.sql(sql).update();
        } finally {
            fixtures.jdbc.sql("alter table audit_log enable trigger audit_log_no_change").update();
        }
    }

    @Test
    void anEmptyLogIsIntactAndHasTheGenesisHead() {
        AuditLog.Verification result = log.verify();
        assertThat(result.intact()).isTrue();
        assertThat(result.checked()).isZero();
        assertThat(log.head()).isEqualTo(AuditLog.GENESIS);
    }

    @Test
    void entriesLinkToEachOtherAndVerify() {
        AuditLog.Entry first =
                log.append("system", "CHANGE_DETECTED", "set-1", Map.of("events", 4));
        fixtures.clock.advance(Duration.ofSeconds(1));
        AuditLog.Entry second = log.append("system", "JOB_CREATED", "job-1", Map.of());
        assertThat(first.previousHash()).isEqualTo(AuditLog.GENESIS);
        assertThat(second.previousHash()).isEqualTo(first.hash());
        assertThat(second.seq()).isGreaterThan(first.seq());
        assertThat(log.head()).isEqualTo(second.hash());
        AuditLog.Verification result = log.verify();
        assertThat(result.intact()).isTrue();
        assertThat(result.checked()).isEqualTo(2);
    }

    @Test
    void aLongChainVerifiesAcrossBatches() {
        fill(1100);
        AuditLog.Verification result = log.verify();
        assertThat(result.intact()).isTrue();
        assertThat(result.checked()).isEqualTo(1100);
        assertThat(log.verify(log.head()).intact()).isTrue();
    }

    @Test
    void databaseTriggersRefuseUpdatesDeletesAndTruncation() {
        fill(3);
        assertThatThrownBy(() -> fixtures.jdbc.sql("update audit_log set actor = 'x'").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append only");
        assertThatThrownBy(() -> fixtures.jdbc.sql("delete from audit_log").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> fixtures.jdbc.sql("truncate audit_log").update())
                .isInstanceOf(DataAccessException.class);
        assertThat(log.verify().intact()).isTrue();
    }

    @Test
    void editingTheDataOfAnEntryIsDetectedAtThatEntry() {
        fill(6);
        long seq =
                fixtures.jdbc
                        .sql("select seq from audit_log order by seq offset 3 limit 1")
                        .query(Long.class)
                        .single();
        tamper(
                "update audit_log set data = '{\"step\": 99, \"ok\": true}'::jsonb where seq = "
                        + seq);
        AuditLog.Verification result = log.verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.firstBadSeq()).isEqualTo(seq);
        assertThat(result.problem()).contains("hash");
    }

    @Test
    void editingTheActorOrTheTimeIsDetected() {
        fill(4);
        long seq =
                fixtures.jdbc
                        .sql("select seq from audit_log order by seq offset 1 limit 1")
                        .query(Long.class)
                        .single();
        tamper("update audit_log set actor = 'intruder' where seq = " + seq);
        assertThat(log.verify().firstBadSeq()).isEqualTo(seq);
        tamper("update audit_log set actor = 'worker-1' where seq = " + seq);
        assertThat(log.verify().intact()).isTrue();
        tamper("update audit_log set at = at + interval '1 second' where seq = " + seq);
        assertThat(log.verify().firstBadSeq()).isEqualTo(seq);
    }

    @Test
    void deletingAnEntryBreaksTheLinkOfTheNextOne() {
        fill(5);
        List<Long> seqs =
                fixtures.jdbc
                        .sql("select seq from audit_log order by seq")
                        .query(Long.class)
                        .list();
        tamper("delete from audit_log where seq = " + seqs.get(2));
        AuditLog.Verification result = log.verify();
        assertThat(result.intact()).isFalse();
        assertThat(result.firstBadSeq()).isEqualTo(seqs.get(3));
        assertThat(result.problem()).contains("link");
    }

    @Test
    void aForgedEntryAtTheEndIsCaughtByTheRecordedHead() {
        fill(3);
        String head = log.head();
        tamper(
                "insert into audit_log (at, actor, action, subject, data, prev_hash, hash)"
                        + " values (now(), 'x', 'y', 'z', '{}'::jsonb, '"
                        + head
                        + "', 'ffff')");
        assertThat(log.verify().intact()).isFalse();
        fixtures.jdbc.sql("alter table audit_log disable trigger audit_log_no_change").update();
        fixtures.jdbc.sql("delete from audit_log where hash = 'ffff'").update();
        fixtures.jdbc.sql("alter table audit_log enable trigger audit_log_no_change").update();
        assertThat(log.verify(head).intact()).isTrue();
        long last = fixtures.jdbc.sql("select max(seq) from audit_log").query(Long.class).single();
        tamper("delete from audit_log where seq = " + last);
        AuditLog.Verification truncated = log.verify(head);
        assertThat(truncated.intact()).isFalse();
        assertThat(truncated.problem()).contains("recorded head");
    }

    @Test
    void concurrentWritersStillProduceOneLinearChain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < 12; t++) {
            int writer = t;
            pool.submit(
                    () -> {
                        start.await();
                        for (int i = 0; i < 15; i++) {
                            log.append("writer-" + writer, "EVENT", "shared", Map.of("i", i));
                        }
                        return null;
                    });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        AuditLog.Verification result = log.verify();
        assertThat(result.intact()).isTrue();
        assertThat(result.checked()).isEqualTo(180);
        assertThat(
                        fixtures.jdbc
                                .sql("select count(distinct prev_hash) from audit_log")
                                .query(Long.class)
                                .single())
                .isEqualTo(180);
    }

    @Test
    void entriesForASubjectComeBackInOrderWithTheirData() {
        fill(6);
        List<AuditLog.Entry> entries = log.forSubject("job-0", 10);
        assertThat(entries).hasSize(3);
        assertThat(entries).extracting(e -> e.data().get("step").asInt()).containsExactly(0, 2, 4);
        assertThat(log.forSubject("job-0", 2)).hasSize(2);
        assertThat(log.forSubject("nothing", 5)).isEmpty();
    }

    @Test
    void theNewestEntriesComeFirst() {
        fill(5);
        List<AuditLog.Entry> recent = log.recent(3);
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).seq()).isGreaterThan(recent.get(1).seq());
        assertThat(recent.get(0).data().get("step").asInt()).isEqualTo(4);
        assertThat(log.recent(100)).hasSize(5);
    }

    @Test
    void requiredFieldsAreChecked() {
        assertThatThrownBy(() -> log.append(" ", "A", "s", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> log.append("a", null, "s", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(log.append("a", "B", "s", null).data().size()).isZero();
    }

    @Test
    void theCanonicalFormIgnoresKeyOrderAndWhitespace() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("b", 1);
        one.put("a", List.of("x", Map.of("z", 1, "y", "q\"uote\n")));
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("a", List.of("x", Map.of("y", "q\"uote\n", "z", 1)));
        two.put("b", 1);
        assertThat(Canonical.of(Json.tree(one))).isEqualTo(Canonical.of(Json.tree(two)));
        assertThat(Canonical.of(Json.tree("{ \"k\" : [ 1 , true , null ] }")))
                .isEqualTo("{\"k\":[1,true,null]}");
        assertThat(Canonical.of(Json.tree(Map.of("c", "\u0001\t\r\\"))))
                .isEqualTo("{\"c\":\"\\u0001\\t\\r\\\\\"}");
    }
}
