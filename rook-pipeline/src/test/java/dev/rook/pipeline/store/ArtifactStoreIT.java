package dev.rook.pipeline.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.Severity;
import dev.rook.pipeline.PipelineHarness;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactStoreIT {

    @TempDir Path temp;

    private PipelineHarness harness;
    private ArtifactStore store;
    private UUID job;

    @BeforeEach
    void setUp() {
        harness = new PipelineHarness(temp);
        harness.seedShop();
        store = harness.artifacts;
        job = harness.job(List.of(PipelineHarness.renameEvent()));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private static ImpactReport report() {
        ImpactLocation location =
                new ImpactLocation(
                        "src/main/java/com/shop/Service.java",
                        8,
                        23,
                        8,
                        "client.charge(5)",
                        "com.shop.Service#go",
                        "METHOD_CALL",
                        Confidence.RESOLVED,
                        "com.acme.pay.PaymentClient#charge(long)",
                        false);
        Impact impact =
                new Impact(
                        "fp-1",
                        ChangeKind.METHOD_RENAMED,
                        Severity.BREAKING,
                        "com.acme.pay.PaymentClient#charge(long)",
                        "charge renamed",
                        Map.of("replacement", "createCharge"),
                        List.of(location));
        return ImpactReport.of(List.of(impact));
    }

    @Test
    void anImpactReportRoundTripsAndCanBeReplaced() {
        assertThat(store.impact(job)).isEmpty();
        store.saveImpact(
                job,
                "NEEDS_MIGRATION",
                "1 of 1 changes touch the repository",
                "a".repeat(40),
                report());
        ArtifactStore.StoredImpact stored = store.impact(job).orElseThrow();
        assertThat(stored.decision()).isEqualTo("NEEDS_MIGRATION");
        assertThat(stored.baseSha()).isEqualTo("a".repeat(40));
        assertThat(stored.report()).isEqualTo(report());
        store.saveImpact(job, "NO_IMPACT", "nothing", "b".repeat(40), ImpactReport.of(List.of()));
        assertThat(store.impact(job).orElseThrow().decision()).isEqualTo("NO_IMPACT");
        assertThat(
                        harness.jdbc
                                .sql("select count(*) from impact_report")
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    private ArtifactStore.NewProposal proposal(int attempt, String status, long cost) {
        return new ArtifactStore.NewProposal(
                job,
                attempt,
                "codemod-only",
                status,
                "renamed the call",
                0.9,
                "diff --git a/x b/x",
                Map.of("src/main/java/com/shop/Service.java", "class Service {}\n"),
                List.of("old.txt"),
                List.of("src/Other.java:3 METHOD_CALL"),
                Map.of("inputTokens", 10, "outputTokens", 4),
                cost,
                List.of(Map.of("sequence", 1, "kind", "tool", "name", "read_file")));
    }

    @Test
    void proposalsKeepTheirFilesDeletionsAndTrace() {
        store.saveProposal(proposal(0, "COMPLETE", 120));
        ArtifactStore.StoredProposal stored = store.latestProposal(job).orElseThrow();
        assertThat(stored.strategy()).isEqualTo("codemod-only");
        assertThat(stored.confidence()).isEqualTo(0.9);
        assertThat(stored.files())
                .containsEntry("src/main/java/com/shop/Service.java", "class Service {}\n");
        assertThat(stored.deletions()).containsExactly("old.txt");
        assertThat(stored.unresolved()).containsExactly("src/Other.java:3 METHOD_CALL");
        assertThat(stored.costMicros()).isEqualTo(120);
        assertThat(stored.trace().get(0).get("name").asString()).isEqualTo("read_file");
        assertThat(stored.diff()).startsWith("diff --git");
    }

    @Test
    void theLatestAttemptWinsAndAnAttemptIsReplacedInPlace() {
        store.saveProposal(proposal(0, "PARTIAL", 10));
        store.saveProposal(proposal(1, "COMPLETE", 20));
        store.saveProposal(proposal(1, "VERIFIED", 30));
        ArtifactStore.StoredProposal latest = store.latestProposal(job).orElseThrow();
        assertThat(latest.attempt()).isEqualTo(1);
        assertThat(latest.status()).isEqualTo("VERIFIED");
        assertThat(latest.costMicros()).isEqualTo(30);
        assertThat(
                        harness.jdbc
                                .sql("select count(*) from patch_proposal")
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void aProposalWithoutConfidenceIsKept() {
        store.saveProposal(
                new ArtifactStore.NewProposal(
                        job,
                        0,
                        "codemod-only",
                        "COMPLETE",
                        null,
                        null,
                        "",
                        Map.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        0,
                        List.of()));
        ArtifactStore.StoredProposal stored = store.latestProposal(job).orElseThrow();
        assertThat(stored.confidence()).isNull();
        assertThat(stored.summary()).isNull();
        assertThat(stored.files()).isEmpty();
    }

    @Test
    void verificationRunsRoundTripWithTheirCounts() {
        assertThat(store.latestVerification(job)).isEmpty();
        store.saveVerification(
                new ArtifactStore.NewVerification(
                        job,
                        0,
                        true,
                        true,
                        "all 11 gates passed",
                        40,
                        1,
                        40,
                        1,
                        Map.of("gates", List.of("build")),
                        5_000));
        store.saveVerification(
                new ArtifactStore.NewVerification(
                        job,
                        1,
                        false,
                        false,
                        "verification is not possible",
                        null,
                        null,
                        null,
                        null,
                        Map.of(),
                        12));
        ArtifactStore.StoredVerification latest = store.latestVerification(job).orElseThrow();
        assertThat(latest.attempt()).isEqualTo(1);
        assertThat(latest.passed()).isFalse();
        assertThat(latest.supported()).isFalse();
        assertThat(latest.baselineRun()).isNull();
        store.saveVerification(
                new ArtifactStore.NewVerification(
                        job,
                        1,
                        true,
                        true,
                        "fine",
                        40,
                        1,
                        41,
                        1,
                        Map.of("gates", List.of("build", "tests")),
                        700));
        ArtifactStore.StoredVerification replaced = store.latestVerification(job).orElseThrow();
        assertThat(replaced.passed()).isTrue();
        assertThat(replaced.patchedRun()).isEqualTo(41);
        assertThat(replaced.report().get("gates").size()).isEqualTo(2);
        assertThat(replaced.elapsedMillis()).isEqualTo(700);
    }

    @Test
    void artifactsDisappearWithTheirJob() {
        store.saveImpact(job, "NEEDS_MIGRATION", "s", "a".repeat(40), report());
        store.saveProposal(proposal(0, "COMPLETE", 1));
        harness.jdbc.sql("delete from migration_job where id = :id").param("id", job).update();
        assertThat(
                        harness.jdbc
                                .sql("select count(*) from impact_report")
                                .query(Integer.class)
                                .single())
                .isZero();
        assertThat(
                        harness.jdbc
                                .sql("select count(*) from patch_proposal")
                                .query(Integer.class)
                                .single())
                .isZero();
    }
}
