package dev.rook.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.strategy.CodemodOnlyStrategy;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.control.policy.ApprovalMode;
import dev.rook.control.policy.Policy;
import dev.rook.github.publish.PrPublisher;
import dev.rook.pipeline.step.ApprovalService;
import dev.rook.pipeline.step.IndexingHandler;
import dev.rook.pipeline.step.ProposingHandler;
import dev.rook.pipeline.step.PublishingHandler;
import dev.rook.pipeline.step.StrategyProvider;
import dev.rook.pipeline.step.VerifyingHandler;
import dev.rook.pipeline.verify.PatchVerifier;
import dev.rook.workflow.JobRunner;
import dev.rook.workflow.RunResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineEndToEndIT {

    @TempDir Path temp;

    private PipelineHarness harness;
    private JobRunner runner;
    private ApprovalService approvals;

    @BeforeEach
    void setUp() {
        harness = new PipelineHarness(temp);
        harness.seedShop();
        PatchVerifier verifier = input -> Outcomes.passing();
        PrPublisher publisher =
                new PrPublisher(
                        harness.client, harness.tokens, harness.pullRequests, harness.workRoot);
        runner =
                harness.runner(
                        List.of(
                                new IndexingHandler(
                                        harness.ingestion::load,
                                        harness.access,
                                        harness.artifacts,
                                        harness.audit,
                                        harness.workRoot,
                                        RepoAnalyzer.Options.defaults().withoutSymbolResolution()),
                                new ProposingHandler(
                                        harness.ingestion::load,
                                        harness.access,
                                        harness.repositories,
                                        harness.policies,
                                        harness.artifacts,
                                        harness.budgets,
                                        harness.audit,
                                        jobId ->
                                                StrategyProvider.Prepared.free(
                                                        new CodemodOnlyStrategy()),
                                        harness.workRoot),
                                new VerifyingHandler(
                                        harness.access,
                                        harness.repositories,
                                        harness.policies,
                                        harness.approvals,
                                        harness.artifacts,
                                        verifier,
                                        harness.audit,
                                        harness.workRoot),
                                new PublishingHandler(
                                        harness.ingestion::load,
                                        harness.repositories,
                                        harness.artifacts,
                                        harness.policies,
                                        harness.client,
                                        publisher,
                                        harness.audit,
                                        "https://rook.test")));
        approvals = new ApprovalService(harness.approvals, harness.jobs, harness.audit);
    }

    @AfterEach
    void tearDown() {
        runner.close();
        harness.close();
    }

    private void runUntilIdle() {
        for (int i = 0; i < 12; i++) {
            if (runner.runOnce("w1").idleRun()) {
                return;
            }
        }
    }

    private String remoteFile(String branch, String path) throws Exception {
        Path clone = temp.resolve("verify-" + System.nanoTime());
        try (Git git =
                Git.cloneRepository()
                        .setURI(harness.remote.toUri().toString())
                        .setBranch(branch)
                        .setDirectory(clone.toFile())
                        .call()) {
            return Files.readString(clone.resolve(path)).replace("\r\n", "\n");
        }
    }

    @Test
    void aBreakingChangeBecomesAVerifiedPullRequest() throws Exception {
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        runUntilIdle();
        assertThat(harness.state(job)).isEqualTo("COMPLETED");
        assertThat(harness.github.pulls()).hasSize(1);
        var pull = harness.github.pulls().get(0);
        assertThat(pull.repository()).isEqualTo("acme/shop");
        assertThat(pull.base()).isEqualTo("main");
        assertThat(pull.title()).isEqualTo("Update for acme-sdk 1.0.0 to 2.0.0");
        assertThat(pull.body())
                .contains("charge renamed to createCharge")
                .contains("`src/main/java/com/shop/Service.java:9`")
                .contains("codemod-only")
                .contains("After the change: 2 tests ran, 0 failed.")
                .contains("Job details: https://rook.test/jobs/" + job);
        assertThat(remoteFile(pull.head(), PipelineHarness.SERVICE))
                .contains("client.createCharge(5)")
                .doesNotContain("client.charge(5)");
        assertThat(harness.pullRequests.findByJob(job).orElseThrow().number()).isEqualTo(1);
        assertThat(harness.audit.forSubject("job:" + job, 20))
                .extracting(e -> e.action())
                .containsExactly(
                        "IMPACT_ANALYZED", "PATCH_PROPOSED", "POLICY_DECIDED", "PR_OPENED");
        assertThat(harness.audit.verify().intact()).isTrue();
        assertThat(harness.jobs.events(job, 0, 50))
                .extracting(e -> e.kind())
                .contains("JOB_CREATED", "STEP_STARTED");
        assertThat(harness.github.checkRuns())
                .singleElement()
                .satisfies(run -> assertThat(run.conclusion()).isEqualTo("success"));
    }

    @Test
    void aReviewedPolicyHoldsTheJobUntilSomeoneApproves() {
        harness.policies.save(
                harness.installation.id(),
                null,
                Policy.standard().withApproval(ApprovalMode.ALWAYS));
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        runUntilIdle();
        assertThat(harness.state(job)).isEqualTo("AWAITING_APPROVAL");
        assertThat(harness.github.pulls()).isEmpty();
        assertThat(approvals.approve(job, "reviewer-1", "go ahead"))
                .isEqualTo(ApprovalService.Result.APPLIED);
        runUntilIdle();
        assertThat(harness.state(job)).isEqualTo("COMPLETED");
        assertThat(harness.github.pulls()).hasSize(1);
        assertThat(harness.github.pulls().get(0).body()).contains("Review requested");
        assertThat(harness.audit.forSubject("job:" + job, 20))
                .extracting(e -> e.action())
                .contains("APPROVAL_REQUESTED", "APPROVED", "PR_OPENED");
    }

    @Test
    void aRejectedJobNeverReachesGithub() {
        harness.policies.save(
                harness.installation.id(),
                null,
                Policy.standard().withApproval(ApprovalMode.ALWAYS));
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        runUntilIdle();
        approvals.reject(job, "reviewer-1", "not now");
        runUntilIdle();
        assertThat(harness.state(job)).isEqualTo("REJECTED");
        assertThat(harness.github.pulls()).isEmpty();
        assertThat(
                        harness.github.requests().stream()
                                .filter(
                                        r ->
                                                r.method().equals("POST")
                                                        && r.path().contains("/pulls")))
                .isEmpty();
    }

    @Test
    void aGithubOutageDuringPublishingIsRetriedWithoutDuplicates() throws Exception {
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        for (int i = 0; i < 4; i++) {
            harness.github.failNext("POST", "/pulls", 500, Map.of());
        }
        for (int i = 0; i < 3; i++) {
            runner.runOnce("w1");
        }
        RunResult stuck = runner.runOnce("w1");
        assertThat(stuck.outcome()).isEqualTo(RunResult.Outcome.RETRY_SCHEDULED);
        assertThat(harness.state(job)).isEqualTo("PUBLISHING");
        harness.clock.advance(Duration.ofMinutes(10));
        runUntilIdle();
        assertThat(harness.state(job)).isEqualTo("COMPLETED");
        assertThat(harness.github.pulls()).hasSize(1);
        assertThat(harness.pullRequests.findByJob(job)).isPresent();
    }

    @Test
    void aLongRateLimitReschedulesTheStepAtTheSuggestedTime() {
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        for (int i = 0; i < 3; i++) {
            runner.runOnce("w1");
        }
        harness.github.failNext("POST", "/pulls", 429, Map.of("Retry-After", "600"));
        Instant before = harness.clock.instant();
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.RETRY_SCHEDULED);
        Instant next = harness.jobs.find(job).orElseThrow().nextRunAt();
        assertThat(Duration.between(before, next)).isGreaterThanOrEqualTo(Duration.ofSeconds(500));
        assertThat(harness.github.pulls()).isEmpty();
    }

    @Test
    void aMissingPermissionFailsTheJobInsteadOfRetryingForever() {
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        for (int i = 0; i < 3; i++) {
            runner.runOnce("w1");
        }
        harness.github.failNext("GET", "/repos/acme/shop", 403, Map.of());
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.FAILED);
        assertThat(harness.state(job)).isEqualTo("FAILED");
    }
}
