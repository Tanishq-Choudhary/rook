package dev.rook.pipeline.step;

import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.project.RepoProfile;
import dev.rook.control.audit.AuditLog;
import dev.rook.control.policy.Decision;
import dev.rook.control.policy.Policy;
import dev.rook.control.policy.PolicyEngine;
import dev.rook.control.policy.PolicyInput;
import dev.rook.control.store.ApprovalStore;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.error.RookException;
import dev.rook.core.job.JobState;
import dev.rook.github.git.GitCheckout;
import dev.rook.pipeline.access.RepositoryAccess;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.pipeline.verify.PatchVerifier;
import dev.rook.pipeline.verify.PatchedTree;
import dev.rook.pipeline.verify.VerificationSummary;
import dev.rook.verify.run.VerificationOutcome;
import dev.rook.workflow.Job;
import dev.rook.workflow.JobContext;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.StepResult;
import dev.rook.workflow.repo.RepositoryStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VerifyingHandler implements StepHandler {

    private final RepositoryAccess access;
    private final RepositoryStore repositories;
    private final PolicyStore policies;
    private final ApprovalStore approvals;
    private final ArtifactStore artifacts;
    private final PatchVerifier verifier;
    private final AuditLog audit;
    private final Path workRoot;

    public VerifyingHandler(
            RepositoryAccess access,
            RepositoryStore repositories,
            PolicyStore policies,
            ApprovalStore approvals,
            ArtifactStore artifacts,
            PatchVerifier verifier,
            AuditLog audit,
            Path workRoot) {
        this.access = access;
        this.repositories = repositories;
        this.policies = policies;
        this.approvals = approvals;
        this.artifacts = artifacts;
        this.verifier = verifier;
        this.audit = audit;
        this.workRoot = workRoot;
    }

    @Override
    public JobState state() {
        return JobState.VERIFYING;
    }

    @Override
    public StepResult execute(JobContext context) {
        Job job = context.job();
        String subject = "job:" + job.id();
        ArtifactStore.StoredProposal proposal =
                artifacts
                        .latestProposal(job.id())
                        .orElseThrow(() -> RookException.conflict("the job has no proposal"));
        ArtifactStore.StoredImpact impact =
                artifacts
                        .impact(job.id())
                        .orElseThrow(() -> RookException.conflict("the job has no impact report"));
        RepositoryStore.Repository repository =
                repositories
                        .find(job.repositoryId())
                        .orElseThrow(
                                () -> RookException.notFound("repository", job.repositoryId()));
        PolicyStore.Stored stored =
                policies.effective(repository.installationId(), repository.id());
        Policy policy = stored.policy();

        Path baselineDir = workRoot.resolve("baseline-" + job.id());
        Path patchedDir = workRoot.resolve("patched-" + job.id());
        VerificationOutcome outcome;
        try {
            access.checkout(job.repositoryId(), baselineDir, impact.baseSha());
            context.heartbeat();
            GitCheckout.deleteTree(patchedDir);
            PatchedTree.create(baselineDir, patchedDir, proposal.files(), proposal.deletions());
            outcome =
                    verifier.verify(
                            new PatchVerifier.Input(
                                    baselineDir,
                                    patchedDir,
                                    proposal.diff(),
                                    repository.installationId(),
                                    repository.id(),
                                    RepoProfile.detect(patchedDir).config(),
                                    policy.allowBuildFileChanges()));
        } finally {
            GitCheckout.deleteTree(baselineDir);
            GitCheckout.deleteTree(patchedDir);
        }
        artifacts.saveVerification(
                new ArtifactStore.NewVerification(
                        job.id(),
                        proposal.attempt(),
                        outcome.passed(),
                        outcome.supported(),
                        outcome.summary(),
                        VerificationSummary.executed(outcome.baseline()),
                        VerificationSummary.broken(outcome.baseline()),
                        VerificationSummary.executed(outcome.patched()),
                        VerificationSummary.broken(outcome.patched()),
                        VerificationSummary.report(outcome),
                        outcome.elapsed().toMillis()));
        List<String> changed = new ArrayList<>(proposal.files().keySet());
        changed.addAll(proposal.deletions());
        Decision decision =
                PolicyEngine.evaluate(
                        policy,
                        new PolicyInput(
                                changed,
                                changedLines(proposal.diff()),
                                agentConfidence(proposal),
                                outcome.passed(),
                                proposal.costMicros(),
                                weakestImpact(impact, changed)));
        policies.recordDecision(job.id(), decision, stored.version());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("verified", outcome.passed());
        facts.put("outcome", decision.outcome().name());
        facts.put("reasons", decision.reasons().stream().map(r -> r.code()).toList());
        facts.put("policyVersion", stored.version());
        audit.append("rook", "POLICY_DECIDED", subject, facts);
        return switch (decision.outcome()) {
            case DENY ->
                    StepResult.finish(
                            JobState.REJECTED,
                            "policy denied the patch: "
                                    + decision.reasons().stream()
                                            .map(r -> r.code())
                                            .reduce((a, b) -> a + ", " + b)
                                            .orElse(""));
            case REQUIRE_APPROVAL -> {
                approvals.request(job.id(), decision);
                audit.append("rook", "APPROVAL_REQUESTED", subject, Map.of());
                yield StepResult.advance(
                        JobState.AWAITING_APPROVAL, Map.of("policy", "REQUIRE_APPROVAL"));
            }
            case ALLOW -> StepResult.advance(JobState.PUBLISHING, Map.of("policy", "ALLOW"));
        };
    }

    static int changedLines(String diff) {
        int lines = 0;
        for (String line : diff.split("\n")) {
            if ((line.startsWith("+") && !line.startsWith("+++"))
                    || (line.startsWith("-") && !line.startsWith("---"))) {
                lines++;
            }
        }
        return lines;
    }

    private static double agentConfidence(ArtifactStore.StoredProposal proposal) {
        if (proposal.confidence() != null) {
            return proposal.confidence();
        }
        return proposal.strategy().equals("codemod-only") ? 1.0 : 0.5;
    }

    private static Confidence weakestImpact(
            ArtifactStore.StoredImpact impact, List<String> changed) {
        Set<String> files = new HashSet<>(changed);
        Confidence weakest = Confidence.RESOLVED;
        for (Impact entry : impact.report().impacts()) {
            for (ImpactLocation location : entry.locations()) {
                if (files.contains(location.file())
                        && location.confidence().compareTo(weakest) < 0) {
                    weakest = location.confidence();
                }
            }
        }
        return weakest;
    }
}
