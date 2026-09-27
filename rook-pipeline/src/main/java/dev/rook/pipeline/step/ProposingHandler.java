package dev.rook.pipeline.step;

import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.strategy.StrategyOutcome;
import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.JavaIndexer;
import dev.rook.codeintel.project.RepoProfile;
import dev.rook.control.audit.AuditLog;
import dev.rook.control.budget.BudgetExceededException;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.budget.SpendKind;
import dev.rook.control.policy.Policy;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.error.RookException;
import dev.rook.core.job.JobState;
import dev.rook.github.git.GitCheckout;
import dev.rook.pipeline.access.RepositoryAccess;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.workflow.Job;
import dev.rook.workflow.JobContext;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.StepResult;
import dev.rook.workflow.repo.RepositoryStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public class ProposingHandler implements StepHandler {

    private final Function<UUID, Optional<ChangeSet>> changes;
    private final RepositoryAccess access;
    private final RepositoryStore repositories;
    private final PolicyStore policies;
    private final ArtifactStore artifacts;
    private final BudgetService budgets;
    private final AuditLog audit;
    private final StrategyProvider strategies;
    private final Path workRoot;

    public ProposingHandler(
            Function<UUID, Optional<ChangeSet>> changes,
            RepositoryAccess access,
            RepositoryStore repositories,
            PolicyStore policies,
            ArtifactStore artifacts,
            BudgetService budgets,
            AuditLog audit,
            StrategyProvider strategies,
            Path workRoot) {
        this.changes = changes;
        this.access = access;
        this.repositories = repositories;
        this.policies = policies;
        this.artifacts = artifacts;
        this.budgets = budgets;
        this.audit = audit;
        this.strategies = strategies;
        this.workRoot = workRoot;
    }

    @Override
    public JobState state() {
        return JobState.PROPOSING;
    }

    @Override
    public StepResult execute(JobContext context) throws IOException {
        Job job = context.job();
        String subject = "job:" + job.id();
        ChangeSet changeSet =
                changes.apply(job.changeSetId())
                        .orElseThrow(() -> RookException.notFound("change set", job.changeSetId()));
        ArtifactStore.StoredImpact impact =
                artifacts
                        .impact(job.id())
                        .orElseThrow(() -> RookException.conflict("the job has no impact report"));
        RepositoryStore.Repository repository =
                repositories
                        .find(job.repositoryId())
                        .orElseThrow(
                                () -> RookException.notFound("repository", job.repositoryId()));
        Policy policy = policies.effective(repository.installationId(), repository.id()).policy();

        try (StrategyProvider.Prepared prepared = strategies.prepare(job.id())) {
            BudgetService.Reservation reservation;
            try {
                reservation =
                        budgets.reserve(
                                repository.installationId(),
                                job.id(),
                                SpendKind.MODEL,
                                prepared.reserveMicros());
            } catch (BudgetExceededException e) {
                audit.append(
                        "rook",
                        "BUDGET_DENIED",
                        subject,
                        Map.of("scope", e.scope().name(), "wanted", prepared.reserveMicros()));
                return StepResult.finish(JobState.REJECTED, "budget exhausted: " + e.getMessage());
            }
            Path checkoutDir = workRoot.resolve("propose-" + job.id());
            Path workspaceDir = workRoot.resolve("workspace-" + job.id());
            StrategyOutcome outcome;
            Map<String, String> files = new LinkedHashMap<>();
            try {
                RepositoryAccess.Checkout checkout =
                        access.checkout(job.repositoryId(), checkoutDir, impact.baseSha());
                context.heartbeat();
                GitCheckout.deleteTree(workspaceDir);
                Workspace workspace =
                        Workspace.copyOf(
                                checkout.directory(), workspaceDir, workspacePolicy(policy));
                AgentTask task =
                        new AgentTask(
                                checkout.fullName(),
                                changeSet.events(),
                                impact.report(),
                                changeSet.notes(),
                                index(workspace.root()),
                                "");
                outcome = prepared.strategy().run(task, workspace);
                for (String path : outcome.changedPaths()) {
                    files.put(
                            path,
                            Files.readString(
                                    workspace.root().resolve(path), StandardCharsets.UTF_8));
                }
            } catch (RuntimeException | IOException e) {
                budgets.release(reservation.id());
                throw e;
            } finally {
                GitCheckout.deleteTree(checkoutDir);
                GitCheckout.deleteTree(workspaceDir);
            }
            long tokens = outcome.usage().inputTokens() + outcome.usage().outputTokens();
            budgets.settle(reservation.id(), outcome.costMicros(), tokens);
            artifacts.saveProposal(
                    new ArtifactStore.NewProposal(
                            job.id(),
                            0,
                            outcome.strategy(),
                            outcome.status(),
                            null,
                            null,
                            outcome.diff(),
                            files,
                            List.of(),
                            outcome.unresolved(),
                            usage(outcome),
                            outcome.costMicros(),
                            new ArrayList<>(outcome.trace())));
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("strategy", outcome.strategy());
            facts.put("status", outcome.status());
            facts.put("files", files.size());
            facts.put("unresolved", outcome.unresolved().size());
            facts.put("costMicros", outcome.costMicros());
            audit.append(
                    "rook", outcome.hasPatch() ? "PATCH_PROPOSED" : "NO_PATCH", subject, facts);
            if (!outcome.hasPatch()) {
                return StepResult.finish(
                        JobState.FAILED,
                        "no patch could be produced"
                                + (outcome.unresolved().isEmpty()
                                        ? ""
                                        : ": " + outcome.unresolved().get(0)));
            }
            return StepResult.advance(
                    JobState.VERIFYING,
                    Map.of("strategy", outcome.strategy(), "changedFiles", files.size()));
        }
    }

    private static WorkspacePolicy workspacePolicy(Policy policy) {
        WorkspacePolicy base =
                WorkspacePolicy.standard()
                        .withLimits(
                                policy.maxFilesChanged(),
                                Math.max(policy.maxChangedLines(), 1),
                                policy.maxChangedLines());
        return policy.allowBuildFileChanges() ? base.allowingBuildFiles() : base;
    }

    private static CodeIndex index(Path root) {
        RepoProfile profile = RepoProfile.detect(root);
        List<String> roots = new ArrayList<>(profile.mainRoots());
        roots.addAll(profile.testRoots());
        return JavaIndexer.index(root, roots);
    }

    private static Map<String, Object> usage(StrategyOutcome outcome) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", outcome.usage().inputTokens());
        usage.put("outputTokens", outcome.usage().outputTokens());
        usage.put("elapsedMillis", outcome.elapsedMillis());
        return usage;
    }
}
