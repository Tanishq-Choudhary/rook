package dev.rook.pipeline.step;

import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.control.audit.AuditLog;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public class IndexingHandler implements StepHandler {

    private final Function<UUID, Optional<ChangeSet>> changes;
    private final RepositoryAccess access;
    private final ArtifactStore artifacts;
    private final AuditLog audit;
    private final Path workRoot;
    private final RepoAnalyzer.Options options;

    public IndexingHandler(
            Function<UUID, Optional<ChangeSet>> changes,
            RepositoryAccess access,
            ArtifactStore artifacts,
            AuditLog audit,
            Path workRoot,
            RepoAnalyzer.Options options) {
        this.changes = changes;
        this.access = access;
        this.artifacts = artifacts;
        this.audit = audit;
        this.workRoot = workRoot;
        this.options = options;
    }

    @Override
    public JobState state() {
        return JobState.INDEXING;
    }

    @Override
    public StepResult execute(JobContext context) {
        Job job = context.job();
        ChangeSet changeSet =
                changes.apply(job.changeSetId())
                        .orElseThrow(() -> RookException.notFound("change set", job.changeSetId()));
        Path directory = workRoot.resolve("index-" + job.id());
        RepositoryAccess.Checkout checkout = access.checkout(job.repositoryId(), directory, null);
        try {
            context.heartbeat();
            RepoAnalyzer.Analysis analysis =
                    RepoAnalyzer.analyze(checkout.directory(), changeSet.events(), options);
            RepoAnalyzer.Assessment assessment = analysis.assessment();
            artifacts.saveImpact(
                    job.id(),
                    assessment.decision().name(),
                    assessment.summary(),
                    checkout.sha(),
                    analysis.report());
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("decision", assessment.decision().name());
            facts.put("locations", analysis.report().stats().locations());
            facts.put("files", analysis.report().stats().affectedFiles());
            facts.put("baseSha", checkout.sha());
            audit.append("rook", "IMPACT_ANALYZED", "job:" + job.id(), facts);
            if (assessment.decision() != RepoAnalyzer.Decision.NEEDS_MIGRATION) {
                return StepResult.finish(JobState.NO_IMPACT, assessment.summary());
            }
            return StepResult.advance(
                    JobState.PROPOSING,
                    Map.of("baseSha", checkout.sha(), "decision", assessment.decision().name()));
        } finally {
            GitCheckout.deleteTree(directory);
        }
    }
}
