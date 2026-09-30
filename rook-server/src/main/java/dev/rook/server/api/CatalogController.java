package dev.rook.server.api;

import dev.rook.control.auth.Principal;
import dev.rook.control.auth.Scope;
import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.server.api.Dtos.ChangeSetDetail;
import dev.rook.server.api.Dtos.ChangeSetSummary;
import dev.rook.server.api.Dtos.InstallationDto;
import dev.rook.server.api.Dtos.Page;
import dev.rook.server.api.Dtos.RepositoryDto;
import dev.rook.server.api.Dtos.SourceDto;
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
@RequestMapping("/api/v1")
@ConditionalOnProperty(
        prefix = "rook.workflow",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class CatalogController {

    private final ApiAccess access;
    private final ApiQueries queries;

    CatalogController(ApiAccess access, ApiQueries queries) {
        this.access = access;
        this.queries = queries;
    }

    @GetMapping("/installations")
    List<InstallationDto> installations(HttpServletRequest request) {
        Principal principal = access.principal(request, Scope.READ);
        return queries.installations(access.installationFilter(principal, null));
    }

    @GetMapping("/installations/{id}")
    InstallationDto installation(HttpServletRequest request, @PathVariable UUID id) {
        Principal principal = access.principal(request, Scope.READ);
        access.ensureVisible(principal, id);
        return queries.installation(id)
                .orElseThrow(() -> RookException.notFound("installation", id));
    }

    @GetMapping("/repositories")
    Page<RepositoryDto> repositories(
            HttpServletRequest request,
            @RequestParam(required = false) UUID installation,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Principal principal = access.principal(request, Scope.READ);
        return queries.repositories(
                access.installationFilter(principal, installation),
                ApiAccess.pageNumber(page),
                ApiAccess.pageSize(size));
    }

    @GetMapping("/repositories/{id}")
    RepositoryDto repository(HttpServletRequest request, @PathVariable UUID id) {
        Principal principal = access.principal(request, Scope.READ);
        RepositoryDto repository =
                queries.repository(id).orElseThrow(() -> RookException.notFound("repository", id));
        access.ensureVisible(principal, repository.installationId());
        return repository;
    }

    @GetMapping("/sources")
    List<SourceDto> sources(HttpServletRequest request) {
        access.principal(request, Scope.READ);
        return queries.sources();
    }

    @GetMapping("/change-sets")
    Page<ChangeSetSummary> changeSets(
            HttpServletRequest request,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        access.principal(request, Scope.READ);
        return queries.changeSets(source, ApiAccess.pageNumber(page), ApiAccess.pageSize(size));
    }

    @GetMapping("/change-sets/{id}")
    ChangeSetDetail changeSet(HttpServletRequest request, @PathVariable UUID id) {
        access.principal(request, Scope.READ);
        ChangeSetSummary summary =
                queries.changeSet(id).orElseThrow(() -> RookException.notFound("change set", id));
        return new ChangeSetDetail(summary, queries.events(id), Json.tree(queries.notes(id)));
    }
}
