package dev.rook.pipeline.step;

import dev.rook.control.audit.AuditLog;
import dev.rook.control.policy.Decision;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.core.job.JobState;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.api.GitHubException;
import dev.rook.github.publish.PrPublisher;
import dev.rook.github.publish.PrReport;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.workflow.Job;
import dev.rook.workflow.JobContext;
import dev.rook.workflow.StepFailure;
import dev.rook.workflow.StepHandler;
import dev.rook.workflow.StepResult;
import dev.rook.workflow.repo.RepositoryStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public class PublishingHandler implements StepHandler {

    private final Function<UUID, Optional<ChangeSet>> changes;
    private final RepositoryStore repositories;
    private final ArtifactStore artifacts;
    private final PolicyStore policies;
    private final GitHubClient client;
    private final PrPublisher publisher;
    private final AuditLog audit;
    private final String dashboardUrl;

    public PublishingHandler(
            Function<UUID, Optional<ChangeSet>> changes,
            RepositoryStore repositories,
            ArtifactStore artifacts,
            PolicyStore policies,
            GitHubClient client,
            PrPublisher publisher,
            AuditLog audit,
            String dashboardUrl) {
        this.changes = changes;
        this.repositories = repositories;
        this.artifacts = artifacts;
        this.policies = policies;
        this.client = client;
        this.publisher = publisher;
        this.audit = audit;
        this.dashboardUrl = dashboardUrl;
    }

    @Override
    public JobState state() {
        return JobState.PUBLISHING;
    }

    @Override
    public StepResult execute(JobContext context) {
        Job job = context.job();
        ChangeSet changeSet =
                changes.apply(job.changeSetId())
                        .orElseThrow(() -> RookException.notFound("change set", job.changeSetId()));
        ArtifactStore.StoredProposal proposal =
                artifacts
                        .latestProposal(job.id())
                        .orElseThrow(() -> RookException.conflict("the job has no proposal"));
        ArtifactStore.StoredImpact impact =
                artifacts
                        .impact(job.id())
                        .orElseThrow(() -> RookException.conflict("the job has no impact report"));
        ArtifactStore.StoredVerification verification =
                artifacts.latestVerification(job.id()).orElse(null);
        RepositoryStore.Repository repository =
                repositories
                        .find(job.repositoryId())
                        .orElseThrow(
                                () -> RookException.notFound("repository", job.repositoryId()));
        RepositoryStore.Installation installation =
                repositories
                        .installation(repository.installationId())
                        .orElseThrow(
                                () ->
                                        RookException.notFound(
                                                "installation", repository.installationId()));
        Decision decision =
                policies.latestDecision(job.id())
                        .map(PolicyStore.StoredDecision::decision)
                        .orElse(null);
        PrReport report =
                ReportAssembler.assemble(
                        repository.fullName(),
                        changeSet,
                        impact,
                        proposal,
                        verification,
                        decision,
                        dashboardUrl == null ? null : dashboardUrl + "/jobs/" + job.id());
        PrPublisher.Published published;
        try {
            long githubInstallation = installation.githubInstallationId();
            GitHubClient.Repo remote = client.repository(githubInstallation, repository.fullName());
            context.heartbeat();
            published =
                    publisher.publish(
                            new PrPublisher.Request(
                                    githubInstallation,
                                    repository.id(),
                                    remote,
                                    job.id(),
                                    changeSet.sourceId() + " " + changeSet.toVersion(),
                                    proposal.files(),
                                    proposal.deletions(),
                                    report));
        } catch (GitHubException e) {
            if (e.retryAfterMillis() > 0) {
                throw new StepFailure.Retryable(
                        e.getMessage(), e, Duration.ofMillis(e.retryAfterMillis()));
            }
            if (e.status() >= 400 && e.status() < 500) {
                throw new StepFailure.Fatal(e.getMessage(), e);
            }
            throw e;
        } catch (RookException e) {
            if (e.code() == ErrorCode.CONFLICT || e.code() == ErrorCode.INVALID) {
                throw new StepFailure.Fatal(e.getMessage(), e);
            }
            throw e;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("number", published.number());
        facts.put("url", published.url());
        facts.put("branch", published.branch());
        facts.put("created", published.created());
        audit.append("rook", "PR_OPENED", "job:" + job.id(), facts);
        return StepResult.finish(JobState.COMPLETED, "pull request #" + published.number());
    }
}
