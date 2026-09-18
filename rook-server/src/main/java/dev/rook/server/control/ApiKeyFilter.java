package dev.rook.server.control;

import dev.rook.control.auth.Authenticator;
import dev.rook.control.auth.Principal;
import dev.rook.control.limit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL = "rook.principal";

    private final Authenticator authenticator;
    private final RateLimiter keyLimiter;
    private final RateLimiter failureLimiter;
    private final MeterRegistry meters;
    private final String protectedPrefix;

    public ApiKeyFilter(
            Authenticator authenticator,
            RateLimiter keyLimiter,
            RateLimiter failureLimiter,
            MeterRegistry meters,
            String protectedPrefix) {
        this.authenticator = authenticator;
        this.keyLimiter = keyLimiter;
        this.failureLimiter = failureLimiter;
        this.meters = meters;
        this.protectedPrefix = protectedPrefix;
    }

    public static Optional<Principal> principal(HttpServletRequest request) {
        Object value = request.getAttribute(PRINCIPAL);
        return value instanceof Principal principal ? Optional.of(principal) : Optional.empty();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(protectedPrefix);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String credential = credential(request);
        String client = request.getRemoteAddr();
        if (credential == null) {
            if (throttled(response, failureLimiter.tryAcquire(client))) {
                return;
            }
            unauthorized(response, "unauthenticated", "an API key is required");
            return;
        }
        Optional<Principal> principal = authenticator.authenticate(credential);
        if (principal.isEmpty()) {
            if (throttled(response, failureLimiter.tryAcquire(client))) {
                return;
            }
            unauthorized(response, "invalid_key", "the API key is not valid");
            return;
        }
        RateLimiter.Result result = keyLimiter.tryAcquire(principal.get().keyId().toString());
        response.setHeader("X-RateLimit-Remaining", Integer.toString(result.remaining()));
        if (throttled(response, result)) {
            return;
        }
        request.setAttribute(PRINCIPAL, principal.get());
        chain.doFilter(request, response);
    }

    private boolean throttled(HttpServletResponse response, RateLimiter.Result result)
            throws IOException {
        if (result.allowed()) {
            return false;
        }
        meters.counter("rook.http.rejected", "reason", "rate_limited").increment();
        response.setHeader(
                "Retry-After",
                Long.toString(Math.max(1, (result.retryAfterMillis() + 999) / 1000)));
        Problems.write(response, 429, "rate_limited", "too many requests, slow down");
        return true;
    }

    private void unauthorized(HttpServletResponse response, String reason, String detail)
            throws IOException {
        meters.counter("rook.http.rejected", "reason", reason).increment();
        response.setHeader("WWW-Authenticate", "Bearer");
        Problems.write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", detail);
    }

    static String credential(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null) {
            String trimmed = header.strip();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                String token = trimmed.substring(7).strip();
                return token.isEmpty() ? null : token;
            }
        }
        String key = request.getHeader("X-Api-Key");
        return key == null || key.isBlank() ? null : key.strip();
    }
}
