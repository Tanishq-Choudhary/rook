package dev.rook.pipeline.step;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.strategy.CodemodOnlyStrategy;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.control.policy.ApprovalMode;
import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Policy;
import dev.rook.control.store.ApprovalStore;
import dev.rook.core.job.JobState;
import dev.rook.pipeline.Outcomes;
import dev.rook.pipeline.PipelineHarness;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.pipeline.verify.PatchVerifier;
import dev.rook.verify.run.VerificationOutcome;
import dev.rook.workflow.JobContext;
import dev.rook.workflow.JobRunner;
import dev.rook.workflow.RunResult;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.StepResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyingHandlerIT {

    @TempDir Path temp;

    private PipelineHarness harness;
    private JobRunner runner;
    private final List<PatchVerifier.Input> inputs = new ArrayList<>();
    private final List<String> patchedService = new ArrayList<>();
    private final AtomicReference<VerificationOutcome> outcome =
            new AtomicReference<>(Outcomes.passing());

    @BeforeEach
    void setUp() {
        harness = new PipelineHarness(temp);
        harness.seedShop();
        IndexingHandler indexing =
                new IndexingHandler(
                        harness.ingestion::load,
                        harness.access,
                        harness.artifacts,
                        harness.audit,
                        harness.workRoot,
                        RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        ProposingHandler proposing =
                new ProposingHandler(
                        harness.ingestion::load,
                        harness.access,
                        harness.repositories,
                        harness.policies,
                        harness.artifacts,
                        harness.budgets,
                        harness.audit,
                        jobId -> StrategyProvider.Prepared.free(new CodemodOnlyStrategy()),
                        harness.workRoot);
        PatchVerifier verifier =
                input -> {
                    inputs.add(input);
                    try {
                        patchedService.add(
                                Files.readString(input.patched().resolve(PipelineHarness.SERVICE)));
                        assertThat(input.baseline().resolve(PipelineHarness.SERVICE)).exists();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return outcome.get();
                };
        VerifyingHandler verifying =
                new VerifyingHandler(
                        harness.access,
                        harness.repositories,
                        harness.policies,
                        harness.approvals,
                        harness.artifacts,
                        verifier,
                        harness.audit,
                        harness.workRoot);
        StepHandler publishing =
                new StepHandler() {
                    @Override
                    public JobState state() {
                        return JobState.PUBLISHING;
                    }

                    @Override
                    public StepResult execute(JobContext context) {
                        return StepResult.finish(JobState.COMPLETED, "stub");
                    }
                };
        runner = harness.runner(List.of(indexing, proposing, verifying, publishing));
    }

    @AfterEach
    void tearDown() {
        runner.close();
        harness.close();
    }

    private UUID jobAtVerifying() {
        UUID job = harness.job(List.of(PipelineHarness.renameEvent()));
        runner.runOnce("w1");
        runner.runOnce("w1");
        assertThat(harness.state(job)).isEqualTo("VERIFYING");
        return job;
    }

    @Test
    void aVerifiedSmallPatchIsAllowedStraightToPublishing() {
        UUID job = jobAtVerifying();
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.ADVANCED);
        assertThat(harness.state(job)).isEqualTo("PUBLISHING");
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).installationId()).isEqualTo(harness.installation.id());
        assertThat(inputs.get(0).allowBuildFiles()).isFalse();
        assertThat(patchedService.get(0)).contains("client.createCharge(5)");
        assertThat(inputs.get(0).diff()).contains("+        return client.createCharge(5);");
        ArtifactStore.StoredVerification stored =
                harness.artifacts.latestVerification(job).orElseThrow();
        assertThat(stored.passed()).isTrue();
        assertThat(stored.baselineRun()).isEqualTo(2);
        assertThat(stored.patchedFailed()).isZero();
        assertThat(stored.report().get("gates").size()).isEqualTo(2);
        assertThat(harness.policies.latestDecision(job).orElseThrow().decision().allowed())
                .isTrue();
        assertThat(harness.approvals.find(job)).isEmpty();
        assertThat(harness.audit.forSubject("job:" + job, 10))
                .extracting(e -> e.action())
                .contains("POLICY_DECIDED");
        assertThat(harness.workRoot.resolve("baseline-" + job)).doesNotExist();
        assertThat(harness.workRoot.resolve("patched-" + job)).doesNotExist();
    }

    @Test
    void aFailedVerificationIsDeniedAndTheReasonIsKept() {
        outcome.set(Outcomes.failing());
        UUID job = jobAtVerifying();
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.FINISHED);
        assertThat(harness.state(job)).isEqualTo("REJECTED");
        assertThat(harness.jobs.find(job).orElseThrow().lastError()).contains("UNVERIFIED");
        Decision decision = harness.policies.latestDecision(job).orElseThrow().decision();
        assertThat(decision.denied()).isTrue();
        assertThat(decision.has("UNVERIFIED")).isTrue();
        ArtifactStore.StoredVerification stored =
                harness.artifacts.latestVerification(job).orElseThrow();
        assertThat(stored.passed()).isFalse();
        assertThat(stored.patchedFailed()).isEqualTo(1);
        assertThat(stored.report().get("regressions").get(0).asString())
                .isEqualTo("com.shop.ServiceTest#again");
        assertThat(stored.report().get("confirmedRegressions").size()).isEqualTo(1);
    }

    @Test
    void aRepositoryThatCannotBeVerifiedIsDeniedToo() {
        outcome.set(VerificationOutcome.unsupported("no build file found", Duration.ofMillis(5)));
        UUID job = jobAtVerifying();
        runner.runOnce("w1");
        assertThat(harness.state(job)).isEqualTo("REJECTED");
        ArtifactStore.StoredVerification stored =
                harness.artifacts.latestVerification(job).orElseThrow();
        assertThat(stored.supported()).isFalse();
        assertThat(stored.baselineRun()).isNull();
        assertThat(stored.report().get("reason").asString()).isEqualTo("no build file found");
    }

    @Test
    void aPolicyThatWantsEveryChangeReviewedAsksForApproval() {
        harness.policies.save(
                harness.installation.id(),
                null,
                Policy.standard().withApproval(ApprovalMode.ALWAYS));
        UUID job = jobAtVerifying();
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.ADVANCED);
        assertThat(harness.state(job)).isEqualTo("AWAITING_APPROVAL");
        ApprovalStore.Approval approval = harness.approvals.find(job).orElseThrow();
        assertThat(approval.status()).isEqualTo(ApprovalStore.Status.PENDING);
        assertThat(approval.reasons()).extracting(r -> r.code()).contains("APPROVAL_REQUIRED");
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(RunResult.Outcome.IDLE);
    }

    @Test
    void anApprovalSendsTheJobOnToPublishing() {
        harness.policies.save(
                harness.installation.id(),
                null,
                Policy.standard().withApproval(ApprovalMode.ALWAYS));
        UUID job = jobAtVerifying();
        runner.runOnce("w1");
        ApprovalService service =
                new ApprovalService(harness.approvals, harness.jobs, harness.audit);
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(RunResult.Outcome.IDLE);
        assertThat(service.approve(job, "reviewer-1", "looks right"))
                .isEqualTo(ApprovalService.Result.APPLIED);
        assertThat(service.approve(job, "reviewer-2", null))
                .isEqualTo(ApprovalService.Result.NOT_PENDING);
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.FINISHED);
        assertThat(harness.state(job)).isEqualTo("COMPLETED");
        assertThat(harness.approvals.find(job).orElseThrow().decidedBy()).isEqualTo("reviewer-1");
        assertThat(harness.jobs.find(job).orElseThrow().checkpointText("approvedBy"))
                .isEqualTo("reviewer-1");
        assertThat(harness.audit.forSubject("job:" + job, 20))
                .extracting(e -> e.action())
                .contains("APPROVAL_REQUESTED", "APPROVED");
    }

    @Test
    void aRejectionEndsTheJob() {
        harness.policies.save(
                harness.installation.id(),
                null,
                Policy.standard().withApproval(ApprovalMode.ALWAYS));
        UUID job = jobAtVerifying();
        runner.runOnce("w1");
        ApprovalService service =
                new ApprovalService(harness.approvals, harness.jobs, harness.audit);
        assertThat(service.reject(job, "reviewer-1", "wrong approach"))
                .isEqualTo(ApprovalService.Result.APPLIED);
        assertThat(harness.state(job)).isEqualTo("REJECTED");
        assertThat(harness.approvals.find(job).orElseThrow().status())
                .isEqualTo(ApprovalStore.Status.REJECTED);
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(RunResult.Outcome.IDLE);
    }

    @Test
    void approvingAJobThatWasCancelledMeanwhileIsReported() {
        harness.policies.save(
                harness.installation.id(),
                null,
                Policy.standard().withApproval(ApprovalMode.ALWAYS));
        UUID job = jobAtVerifying();
        runner.runOnce("w1");
        harness.jdbc
                .sql(
                        "update migration_job set state = 'CANCELLED', finished_at = now() where id = :id")
                .param("id", job)
                .update();
        ApprovalService service =
                new ApprovalService(harness.approvals, harness.jobs, harness.audit);
        assertThat(service.approve(job, "reviewer-1", null))
                .isEqualTo(ApprovalService.Result.JOB_NOT_WAITING);
    }

    @Test
    void theChangedLinesAreCountedFromTheDiffBody() {
        String diff =
                "diff --git a/A b/A\n--- a/A\n+++ b/A\n@@ -1,2 +1,2 @@\n context\n-old\n+new\n+extra\n";
        assertThat(VerifyingHandler.changedLines(diff)).isEqualTo(3);
        assertThat(VerifyingHandler.changedLines("")).isZero();
    }
}
