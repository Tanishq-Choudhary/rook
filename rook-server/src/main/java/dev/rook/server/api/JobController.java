package dev.rook.server.api;

import dev.rook.control.auth.Principal;
import dev.rook.control.auth.Scope;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.store.ApprovalStore;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.github.webhook.PullRequests;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.server.api.Dtos.ImpactDto;
import dev.rook.server.api.Dtos.JobDetail;
import dev.rook.server.api.Dtos.JobSummary;
import dev.rook.server.api.Dtos.Page;
import dev.rook.server.api.Dtos.ProposalDto;
import dev.rook.server.api.Dtos.TimelineEntry;
import dev.rook.server.api.Dtos.VerificationDto;
import dev.rook.workflow.JobRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
@ConditionalOnProperty(
        prefix = "rook.workflow",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class JobController {

    private static final int TIMELINE_LIMIT = 500;

    private final ApiAccess access;
    private final ApiQueries queries;
    private final JobRepository jobs;
    private final ArtifactStore artifacts;
    private final PolicyStore policies;
    private final ApprovalStore approvals;
    private final PullRequests pullRequests;
    private final BudgetService budgets;

    JobController(
            ApiAccess access,
            ApiQueries queries,
            JobRepository jobs,
            ArtifactStore artifacts,
            PolicyStore policies,
            ApprovalStore approvals,
            PullRequests pullRequests,
            BudgetService budgets) {
        this.access = access;
        this.queries = queries;
        this.jobs = jobs;
        this.artifacts = artifacts;
        this.policies = policies;
        this.approvals = approvals;
        this.pullRequests = pullRequests;
        this.budgets = budgets;
    }

    @GetMapping
    Page<JobSummary> list(
            HttpServletRequest request,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) UUID repository,
            @RequestParam(required = false) UUID installation,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Principal principal = access.principal(request, Scope.READ);
        if (state != null && !state.matches("[A-Z_]{3,30}")) {
            throw RookException.invalid("the state filter is not valid");
        }
        return queries.jobs(
                access.installationFilter(principal, installation),
                state,
                repository,
                ApiAccess.pageNumber(page),
                ApiAccess.pageSize(size));
    }

    @GetMapping("/{id}")
    JobDetail detail(HttpServletRequest request, @PathVariable UUID id) {
        Principal principal = access.principal(request, Scope.READ);
        JobSummary summary = queries.job(id).orElseThrow(() -> RookException.notFound("job", id));
        access.ensureVisible(
                principal,
                queries.jobInstallation(id).orElseThrow(() -> RookException.notFound("job", id)));
        List<TimelineEntry> timeline =
                jobs.events(id, 0, TIMELINE_LIMIT).stream()
                        .map(e -> new TimelineEntry(e.id(), e.at(), e.kind(), e.state(), e.data()))
                        .toList();
        return new JobDetail(
                summary,
                timeline,
                artifacts
                        .impact(id)
                        .map(
                                i ->
                                        new ImpactDto(
                                                i.decision(),
                                                i.summary(),
                                                i.baseSha(),
                                                Json.tree(i.report())))
                        .orElse(null),
                artifacts
                        .latestProposal(id)
                        .map(
                                p ->
                                        new ProposalDto(
                                                p.strategy(),
                                                p.status(),
                                                p.summary(),
                                                p.confidence(),
                                                p.diff(),
                                                p.files().keySet().stream().sorted().toList(),
                                                p.unresolved(),
                                                p.costMicros(),
                                                p.trace()))
                        .orElse(null),
                artifacts
                        .latestVerification(id)
                        .map(
                                v ->
                                        new VerificationDto(
                                                v.passed(),
                                                v.supported(),
                                                v.summary(),
                                                v.baselineRun(),
                                                v.baselineFailed(),
                                                v.patchedRun(),
                                                v.patchedFailed(),
                                                v.elapsedMillis(),
                                                v.report()))
                        .orElse(null),
                policies.latestDecision(id).map(PolicyStore.StoredDecision::decision).orElse(null),
                approvals.find(id).orElse(null),
                pullRequests.findByJob(id).orElse(null),
                budgets.jobSpend(id));
    }
}
