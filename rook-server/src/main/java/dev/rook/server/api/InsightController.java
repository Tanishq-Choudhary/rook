package dev.rook.server.api;

import dev.rook.control.audit.AuditLog;
import dev.rook.control.auth.Principal;
import dev.rook.control.auth.Scope;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.budget.SpendKind;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.error.RookException;
import dev.rook.server.api.Dtos.AuditEntryDto;
import dev.rook.server.api.Dtos.AuditVerificationDto;
import dev.rook.server.api.Dtos.UsageDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(
        prefix = "rook.workflow",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class InsightController {

    private final ApiAccess access;
    private final BudgetService budgets;
    private final AuditLog audit;
    private final PolicyStore policies;

    InsightController(
            ApiAccess access, BudgetService budgets, AuditLog audit, PolicyStore policies) {
        this.access = access;
        this.budgets = budgets;
        this.audit = audit;
        this.policies = policies;
    }

    @GetMapping("/usage")
    UsageDto usage(HttpServletRequest request, @RequestParam(required = false) UUID installation) {
        UUID target = target(request, installation);
        BudgetService.Usage usage = budgets.usage(target);
        return new UsageDto(
                usage.monthStart(),
                usage.spentMicros(),
                usage.reservedMicros(),
                usage.monthlyCapMicros(),
                usage.remainingMicros(),
                usage.spentByKind().get(SpendKind.MODEL),
                usage.spentByKind().get(SpendKind.SANDBOX));
    }

    @GetMapping("/policies/effective")
    PolicyStore.Stored policy(
            HttpServletRequest request,
            @RequestParam(required = false) UUID installation,
            @RequestParam(required = false) UUID repository) {
        UUID target = target(request, installation);
        return policies.effective(target, repository);
    }

    @GetMapping("/audit")
    List<AuditEntryDto> audit(
            HttpServletRequest request,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) Integer limit) {
        access.principal(request, Scope.ADMIN);
        int size = ApiAccess.pageSize(limit);
        List<AuditLog.Entry> entries =
                subject == null ? audit.recent(size) : audit.forSubject(subject, size);
        return entries.stream()
                .map(
                        e ->
                                new AuditEntryDto(
                                        e.seq(),
                                        e.at(),
                                        e.actor(),
                                        e.action(),
                                        e.subject(),
                                        e.data(),
                                        e.hash()))
                .toList();
    }

    @GetMapping("/audit/verify")
    AuditVerificationDto verify(HttpServletRequest request) {
        access.principal(request, Scope.ADMIN);
        AuditLog.Verification result = audit.verify();
        return new AuditVerificationDto(
                result.intact(),
                result.checked(),
                result.firstBadSeq(),
                result.problem(),
                audit.head());
    }

    private UUID target(HttpServletRequest request, UUID requested) {
        Principal principal = access.principal(request, Scope.READ);
        UUID target = access.installationFilter(principal, requested);
        if (target == null) {
            throw RookException.invalid("an installation is required");
        }
        return target;
    }
}
