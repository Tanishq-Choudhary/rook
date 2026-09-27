package dev.rook.pipeline.step;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.control.audit.AuditLog;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.pipeline.PipelineHarness;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.workflow.JobRunner;
import dev.rook.workflow.RunResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexingHandlerIT {

    @TempDir Path temp;

    private PipelineHarness harness;
    private JobRunner runner;

    @BeforeEach
    void setUp() {
        harness = new PipelineHarness(temp);
        IndexingHandler handler =
                new IndexingHandler(
                        harness.ingestion::load,
                        harness.access,
                        harness.artifacts,
                        harness.audit,
                        harness.workRoot,
                        RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        runner = harness.runner(List.of(handler));
    }

    @AfterEach
    void tearDown() {
        runner.close();
        harness.close();
    }

    private String remoteHead() throws Exception {
        try (Git git = Git.open(harness.remote.toFile())) {
            return git.getRepository().resolve("refs/heads/main").name();
        }
    }

    @Test
    void aRepositoryThatUsesTheChangedApiMovesOnToProposing() throws Exception {
        harness.seedShop();
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.ADVANCED);
        assertThat(harness.state(job)).isEqualTo("PROPOSING");
        ArtifactStore.StoredImpact impact = harness.artifacts.impact(job).orElseThrow();
        assertThat(impact.decision()).isEqualTo("NEEDS_MIGRATION");
        assertThat(impact.baseSha()).isEqualTo(remoteHead());
        assertThat(impact.report().affectedFiles()).containsExactly(PipelineHarness.SERVICE);
        assertThat(harness.jobs.find(job).orElseThrow().checkpointText("baseSha"))
                .isEqualTo(remoteHead());
        List<AuditLog.Entry> entries = harness.audit.forSubject("job:" + job, 10);
        assertThat(entries)
                .singleElement()
                .satisfies(
                        entry -> {
                            assertThat(entry.action()).isEqualTo("IMPACT_ANALYZED");
                            assertThat(entry.data().get("decision").asString())
                                    .isEqualTo("NEEDS_MIGRATION");
                        });
        assertThat(harness.audit.verify().intact()).isTrue();
        assertThat(harness.workRoot.resolve("index-" + job)).doesNotExist();
    }

    @Test
    void aRepositoryThatNeverTouchesTheApiFinishesWithoutImpact() {
        harness.seedRepository(
                "acme/blog",
                Map.of(
                        "src/main/java/com/blog/Post.java",
                        "package com.blog;\n\npublic class Post {}\n",
                        "pom.xml",
                        PipelineHarness.POM));
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.FINISHED);
        assertThat(harness.state(job)).isEqualTo("NO_IMPACT");
        assertThat(harness.artifacts.impact(job).orElseThrow().decision()).isEqualTo("NO_IMPACT");
        assertThat(harness.artifacts.latestProposal(job)).isEmpty();
    }

    @Test
    void aTemporaryGithubFailureSchedulesARetry() {
        harness.seedShop();
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        for (int i = 0; i < 4; i++) {
            harness.github.failNext("/repos/acme/shop", 500, Map.of());
        }
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.RETRY_SCHEDULED);
        assertThat(harness.state(job)).isEqualTo("INDEXING");
        assertThat(harness.artifacts.impact(job)).isEmpty();
    }

    @Test
    void theStepLeavesNothingBehindInTheWorkDirectory() throws Exception {
        harness.seedShop();
        harness.job(List.of(PipelineHarness.renameEvent()));
        runner.runOnce("w1");
        if (Files.exists(harness.workRoot)) {
            try (var children = Files.list(harness.workRoot)) {
                assertThat(children.toList()).isEmpty();
            }
        }
    }

    @Test
    void anEventThatIsNotUsedInTheCodeDoesNotCreateImpact() {
        harness.seedShop();
        ChangeEvent other =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod(
                                "com.acme.pay.PaymentClient", "refund", List.of("long")),
                        Map.of(Attr.NAME, "refund", Attr.REPLACEMENT, "reverse"),
                        "refund renamed");
        UUID job = harness.job(List.of(other));
        runner.runOnce("w1");
        assertThat(harness.state(job)).isEqualTo("NO_IMPACT");
    }
}
