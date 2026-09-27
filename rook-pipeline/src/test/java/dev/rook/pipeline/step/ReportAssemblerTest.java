package dev.rook.pipeline.step;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Reason;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.Severity;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import dev.rook.github.publish.PrReport;
import dev.rook.pipeline.store.ArtifactStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportAssemblerTest {

    private static ChangeSet changeSet() {
        ChangeEvent breaking =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod("a.B", "c", List.of()),
                        Map.of(Attr.NAME, "c"),
                        "B.c is now d");
        ChangeEvent additive =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_ADDED,
                        SymbolRef.javaMethod("a.B", "e", List.of()),
                        Map.of(),
                        "B.e was added");
        return new ChangeSet(
                Ids.newId(),
                "acme-sdk",
                SourceKind.MAVEN,
                "1.0.0",
                "2.0.0",
                List.of(breaking, additive),
                List.of(),
                Instant.parse("2026-10-01T10:00:00Z"));
    }

    private static ArtifactStore.StoredImpact impact() {
        ImpactLocation location =
                new ImpactLocation(
                        "src/A.java",
                        4,
                        3,
                        4,
                        "b.c()",
                        "A#run",
                        "METHOD_CALL",
                        Confidence.RESOLVED,
                        "a.B#c()",
                        false);
        Impact impact =
                new Impact(
                        "fp",
                        ChangeKind.METHOD_RENAMED,
                        Severity.BREAKING,
                        "a.B#c()",
                        "renamed",
                        Map.of(),
                        List.of(location));
        return new ArtifactStore.StoredImpact(
                Ids.newId(),
                "NEEDS_MIGRATION",
                "s",
                "a".repeat(40),
                ImpactReport.of(List.of(impact)));
    }

    private static ArtifactStore.StoredProposal proposal(List<String> unresolved) {
        return new ArtifactStore.StoredProposal(
                Ids.newId(),
                Ids.newId(),
                0,
                "codemod-only",
                "COMPLETE",
                null,
                null,
                "diff --git a/src/A.java b/src/A.java\n--- a/src/A.java\n+++ b/src/A.java\n@@ -1 +1 @@\n-old\n-older\n+new\n",
                Map.of("src/A.java", "new"),
                List.of("src/Gone.java"),
                unresolved,
                0,
                Json.tree("[]"),
                Instant.parse("2026-10-01T10:00:00Z"));
    }

    private static ArtifactStore.StoredVerification verification(int baselineFailed) {
        return new ArtifactStore.StoredVerification(
                Ids.newId(),
                Ids.newId(),
                0,
                true,
                true,
                "ok",
                12,
                baselineFailed,
                12,
                baselineFailed,
                Json.tree(
                        "{\"flaky\":[\"ATest#slow\"],\"gates\":[{\"name\":\"compiles\",\"passed\":true},{\"name\":\"limits\",\"passed\":false},{\"name\":\"tests\",\"passed\":true}]}"),
                900,
                Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    void theReportCarriesWhatTheReviewerNeedsToSee() {
        PrReport report =
                ReportAssembler.assemble(
                        "acme/shop",
                        changeSet(),
                        impact(),
                        proposal(List.of("src/B.java:9 METHOD_CALL")),
                        verification(2),
                        new Decision(
                                Decision.Outcome.REQUIRE_APPROVAL,
                                List.of(new Reason("LARGE_PATCH_LINES", "120 lines changed"))),
                        "https://rook.test/jobs/1");
        assertThat(report.repository()).isEqualTo("acme/shop");
        assertThat(report.sourceName()).isEqualTo("acme-sdk");
        assertThat(report.changes()).containsExactly("B.c is now d");
        assertThat(report.callSites())
                .singleElement()
                .satisfies(
                        site -> {
                            assertThat(site.file()).isEqualTo("src/A.java");
                            assertThat(site.line()).isEqualTo(4);
                            assertThat(site.note()).isEqualTo("method call, resolved");
                        });
        assertThat(report.diff()).isEqualTo(new PrReport.Diff(2, 1, 2));
        assertThat(report.verification().baselineRun()).isEqualTo(12);
        assertThat(report.verification().patchedFailed()).isEqualTo(2);
        assertThat(report.verification().flaky()).containsExactly("ATest#slow");
        assertThat(report.verification().gates()).containsExactly("compiles", "tests");
        assertThat(report.risks())
                .containsExactly(
                        "Not changed automatically: src/B.java:9 METHOD_CALL",
                        "2 tests were already failing before this change and are left alone.",
                        "Review requested: 120 lines changed");
        assertThat(report.strategy()).isEqualTo("codemod-only");
        assertThat(report.jobUrl()).isEqualTo("https://rook.test/jobs/1");
    }

    @Test
    void aMissingVerificationAndDecisionGiveEmptyButValidSections() {
        PrReport report =
                ReportAssembler.assemble(
                        "acme/shop", changeSet(), impact(), proposal(List.of()), null, null, null);
        assertThat(report.verification())
                .isEqualTo(new PrReport.Verification(0, 0, 0, 0, List.of(), List.of()));
        assertThat(report.risks()).isEmpty();
        assertThat(report.jobUrl()).isNull();
    }
}
