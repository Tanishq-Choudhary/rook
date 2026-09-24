package dev.rook.github.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InstallationTokens {

    public record Token(String value, Instant expiresAt) {}

    public interface Issuer {

        Token issue(String appJwt, long installationId);
    }

    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final Issuer issuer;
    private final AppJwt jwt;
    private final Clock clock;
    private final ConcurrentMap<Long, Token> cache = new ConcurrentHashMap<>();

    public InstallationTokens(Issuer issuer, AppJwt jwt, Clock clock) {
        this.issuer = issuer;
        this.jwt = jwt;
        this.clock = clock;
    }

    public String tokenFor(long installationId) {
        Token cached = cache.get(installationId);
        if (usable(cached)) {
            return cached.value();
        }
        return cache.compute(
                        installationId,
                        (id, existing) ->
                                usable(existing) ? existing : issuer.issue(jwt.create(), id))
                .value();
    }

    public void forget(long installationId) {
        cache.remove(installationId);
    }

    private boolean usable(Token token) {
        return token != null && clock.instant().plus(REFRESH_MARGIN).isBefore(token.expiresAt());
    }
}
