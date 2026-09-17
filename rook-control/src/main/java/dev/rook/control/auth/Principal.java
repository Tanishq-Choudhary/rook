package dev.rook.control.auth;

import java.util.Set;
import java.util.UUID;

public record Principal(UUID keyId, UUID installationId, String name, Set<Scope> scopes) {

    public Principal {
        scopes = Set.copyOf(scopes);
    }

    public boolean has(Scope required) {
        return scopes.stream().anyMatch(scope -> scope.implies(required));
    }

    public boolean global() {
        return installationId == null;
    }

    public boolean canAccess(UUID installation) {
        return global() || installationId.equals(installation);
    }
}
