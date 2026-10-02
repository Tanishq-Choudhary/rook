package dev.rook.server.api;

import dev.rook.changes.store.ChangeIngestion;
import dev.rook.control.audit.AuditLog;
import dev.rook.control.auth.ApiKeys;
import dev.rook.control.auth.Principal;
import dev.rook.control.auth.Scope;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.policy.Policy;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.github.sync.RepositorySynchronizer;
import dev.rook.pipeline.step.ApprovalService;
import dev.rook.server.api.Dtos.RepositoryDto;
import dev.rook.workflow.JobRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(
        prefix = "rook.workflow",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class ActionController {

    record NoteRequest(String note) {}

    record ActionResult(String result) {}

    record BudgetRequest(Long monthlyMicros, Long jobMicros) {}

    record KeyRequest(String name, Set<Scope> scopes, UUID installation) {}

    record CreatedKey(UUID id, String key, String prefix) {}

    private final ApiAccess access;
    private final ApiQueries queries;
    private final ApprovalService approvals;
    private final JobRepository jobs;
    private final ChangeIngestion ingestion;
    private final PolicyStore policies;
    private final BudgetService budgets;
    private final ApiKeys keys;
    private final AuditLog audit;
    private final ObjectProvider<RepositorySynchronizer> synchronizer;

    ActionController(
            ApiAccess access,
            ApiQueries queries,
            ApprovalService approvals,
            JobRepository jobs,
            ChangeIngestion ingestion,
            PolicyStore policies,
            BudgetService budgets,
            ApiKeys keys,
            AuditLog audit,
            ObjectProvider<RepositorySynchronizer> synchronizer) {
        this.access = access;
        this.queries = queries;
        this.approvals = approvals;
        this.jobs = jobs;
        this.ingestion = ingestion;
        this.policies = policies;
        this.budgets = budgets;
        this.keys = keys;
        this.audit = audit;
        this.synchronizer = synchronizer;
    }

    @PostMapping("/jobs/{id}/approve")
    ActionResult approve(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody(required = false) NoteRequest body) {
        Principal principal = jobPrincipal(request, id);
        return review(approvals.approve(id, reviewer(principal), note(body)));
    }

    @PostMapping("/jobs/{id}/reject")
    ActionResult reject(
            HttpServletRequest request,
            @PathVariable UUID id,
            @RequestBody(required = false) NoteRequest body) {
        Principal principal = jobPrincipal(request, id);
        return review(approvals.reject(id, reviewer(principal), note(body)));
    }

    @PostMapping("/jobs/{id}/cancel")
    ActionResult cancel(HttpServletRequest request, @PathVariable UUID id) {
        Principal principal = jobPrincipal(request, id);
        if (!jobs.cancel(id, reviewer(principal))) {
            throw RookException.conflict("the job is already finished");
        }
        audit.append(reviewer(principal), "JOB_CANCELLED", "job:" + id, Map.of());
        return new ActionResult("CANCELLED");
    }

    @PostMapping("/jobs/{id}/retry")
    ActionResult retry(HttpServletRequest request, @PathVariable UUID id) {
        Principal principal = jobPrincipal(request, id);
        if (!jobs.requeue(id, reviewer(principal))) {
            throw RookException.conflict("only failed or dead jobs can be retried");
        }
        audit.append(reviewer(principal), "JOB_REQUEUED", "job:" + id, Map.of());
        return new ActionResult("REQUEUED");
    }

    @PostMapping("/change-sets/{id}/replay")
    Map<String, Object> replay(HttpServletRequest request, @PathVariable UUID id) {
        Principal principal = access.principal(request, Scope.ADMIN);
        queries.changeSet(id).orElseThrow(() -> RookException.notFound("change set", id));
        int republished = ingestion.replay(id);
        audit.append(reviewer(principal), "CHANGE_SET_REPLAYED", "change-set:" + id, Map.of());
        return Map.of("republished", republished);
    }

    @PostMapping("/repositories/{id}/rescan")
    ActionResult rescan(HttpServletRequest request, @PathVariable UUID id) {
        Principal principal = access.principal(request, Scope.WRITE);
        RepositoryDto repository =
                queries.repository(id).orElseThrow(() -> RookException.notFound("repository", id));
        access.ensureVisible(principal, repository.installationId());
        RepositorySynchronizer sync = synchronizer.getIfAvailable();
        if (sync == null) {
            throw new RookException(ErrorCode.UNAVAILABLE, "github is not configured");
        }
        sync.defaultBranchPushed(id, "manual");
        audit.append(reviewer(principal), "REPOSITORY_RESCANNED", "repository:" + id, Map.of());
        return new ActionResult("RESCANNED");
    }

    @PutMapping("/policies")
    PolicyStore.Stored savePolicy(
            HttpServletRequest request,
            @RequestParam(required = false) UUID installation,
            @RequestParam(required = false) UUID repository,
            @RequestBody Policy policy) {
        Principal principal = access.principal(request, Scope.ADMIN);
        UUID target = installation(principal, installation);
        if (repository != null) {
            RepositoryDto stored =
                    queries.repository(repository)
                            .orElseThrow(() -> RookException.notFound("repository", repository));
            if (!stored.installationId().equals(target)) {
                throw RookException.notFound("repository", repository);
            }
        }
        PolicyStore.Stored saved = policies.save(target, repository, policy);
        audit.append(
                reviewer(principal),
                "POLICY_UPDATED",
                "installation:" + target,
                Map.of("version", saved.version()));
        return saved;
    }

    @PutMapping("/budgets")
    ActionResult saveBudget(
            HttpServletRequest request,
            @RequestParam(required = false) UUID installation,
            @RequestBody BudgetRequest body) {
        Principal principal = access.principal(request, Scope.ADMIN);
        UUID target = installation(principal, installation);
        if (body == null || body.monthlyMicros() == null || body.jobMicros() == null) {
            throw RookException.invalid("monthlyMicros and jobMicros are required");
        }
        budgets.setBudget(target, new BudgetService.Caps(body.monthlyMicros(), body.jobMicros()));
        audit.append(
                reviewer(principal),
                "BUDGET_UPDATED",
                "installation:" + target,
                Map.of("monthlyMicros", body.monthlyMicros(), "jobMicros", body.jobMicros()));
        return new ActionResult("SAVED");
    }

    @GetMapping("/api-keys")
    List<ApiKeys.Summary> listKeys(
            HttpServletRequest request, @RequestParam(required = false) UUID installation) {
        Principal principal = access.principal(request, Scope.ADMIN);
        return keys.list(principal.global() ? installation : principal.installationId());
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedKey createKey(HttpServletRequest request, @RequestBody KeyRequest body) {
        Principal principal = access.principal(request, Scope.ADMIN);
        UUID target = principal.global() ? body.installation() : principal.installationId();
        if (!principal.global() && body.scopes() != null && body.scopes().contains(Scope.ADMIN)) {
            throw RookException.forbidden("only a global key can create admin keys");
        }
        ApiKeys.Issued issued = keys.issue(target, body.name(), body.scopes());
        audit.append(
                reviewer(principal),
                "API_KEY_CREATED",
                "api-key:" + issued.id(),
                Map.of("name", body.name(), "prefix", issued.prefix()));
        return new CreatedKey(issued.id(), issued.key(), issued.prefix());
    }

    @DeleteMapping("/api-keys/{id}")
    ActionResult revokeKey(HttpServletRequest request, @PathVariable UUID id) {
        Principal principal = access.principal(request, Scope.ADMIN);
        if (!principal.global()) {
            boolean own =
                    keys.list(principal.installationId()).stream().anyMatch(k -> k.id().equals(id));
            if (!own) {
                throw RookException.notFound("api key", id);
            }
        }
        if (!keys.revoke(id)) {
            throw RookException.notFound("api key", id);
        }
        audit.append(reviewer(principal), "API_KEY_REVOKED", "api-key:" + id, Map.of());
        return new ActionResult("REVOKED");
    }

    private Principal jobPrincipal(HttpServletRequest request, UUID jobId) {
        Principal principal = access.principal(request, Scope.WRITE);
        UUID installation =
                queries.jobInstallation(jobId)
                        .orElseThrow(() -> RookException.notFound("job", jobId));
        access.ensureVisible(principal, installation);
        return principal;
    }

    private UUID installation(Principal principal, UUID requested) {
        UUID target = access.installationFilter(principal, requested);
        if (target == null) {
            throw RookException.invalid("an installation is required");
        }
        return target;
    }

    private static ActionResult review(ApprovalService.Result result) {
        return switch (result) {
            case APPLIED -> new ActionResult("APPLIED");
            case NOT_PENDING -> throw RookException.conflict("the job has no pending approval");
            case JOB_NOT_WAITING -> throw RookException.conflict("the job is no longer waiting");
        };
    }

    private static String reviewer(Principal principal) {
        return "key:" + principal.name();
    }

    private static String note(NoteRequest body) {
        return body == null ? null : body.note();
    }
}
