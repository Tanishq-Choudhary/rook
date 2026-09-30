package dev.rook.server.api;

import dev.rook.control.auth.Principal;
import dev.rook.control.auth.Scope;
import dev.rook.core.error.RookException;
import dev.rook.server.control.ApiKeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ApiAccess {

    public Principal principal(HttpServletRequest request, Scope required) {
        Principal principal =
                ApiKeyFilter.principal(request)
                        .orElseThrow(
                                () -> RookException.unauthorized("authentication is required"));
        if (!principal.has(required)) {
            throw RookException.forbidden("this key lacks the " + required.name() + " scope");
        }
        return principal;
    }

    public UUID installationFilter(Principal principal, UUID requested) {
        if (principal.global()) {
            return requested;
        }
        if (requested != null && !requested.equals(principal.installationId())) {
            throw RookException.notFound("installation", requested);
        }
        return principal.installationId();
    }

    public void ensureVisible(Principal principal, UUID installationId) {
        if (!principal.canAccess(installationId)) {
            throw RookException.notFound("resource", installationId);
        }
    }

    public static int pageSize(Integer requested) {
        if (requested == null) {
            return 25;
        }
        return Math.max(1, Math.min(100, requested));
    }

    public static int pageNumber(Integer requested) {
        return requested == null ? 0 : Math.max(0, requested);
    }
}
