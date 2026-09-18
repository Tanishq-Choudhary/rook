package dev.rook.server.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.rook.control.auth.Authenticator;
import dev.rook.control.auth.Principal;
import dev.rook.control.auth.Scope;
import dev.rook.control.limit.RateLimiter;
import dev.rook.testkit.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FilterTest {

    private static final UUID KEY = UUID.randomUUID();
    private static final Principal PRINCIPAL =
            new Principal(KEY, UUID.randomUUID(), "ci", EnumSet.of(Scope.READ));

    private final TestClock clock = TestClock.at("2026-10-01T00:00:00Z");
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicInteger reached = new AtomicInteger();
    private final FilterChain chain = (request, response) -> reached.incrementAndGet();

    private final Authenticator authenticator =
            presented -> presented.equals("good-key") ? Optional.of(PRINCIPAL) : Optional.empty();

    private ApiKeyFilter keyFilter(int burst, int failures) {
        return new ApiKeyFilter(
                authenticator,
                new RateLimiter(clock, burst, 1, 100),
                new RateLimiter(clock, failures, 1, 100),
                meters,
                "/api/");
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("10.1.2.3");
        return request;
    }

    private double rejected(String reason) {
        return meters.counter("rook.http.rejected", "reason", reason).count();
    }

    @Test
    void requestsOutsideTheProtectedPrefixPassWithoutACredential() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        keyFilter(5, 5).doFilter(get("/actuator/health"), response, chain);
        assertThat(reached).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aMissingCredentialIsAProblemWithAChallenge() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        keyFilter(5, 5).doFilter(get("/api/jobs"), response, chain);
        assertThat(reached).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("urn:rook:error:unauthorized")
                .doesNotContain("Exception");
        assertThat(rejected("unauthenticated")).isEqualTo(1);
    }

    @Test
    void bearerAndHeaderCredentialsAreBothAccepted() throws Exception {
        MockHttpServletRequest bearer = get("/api/jobs");
        bearer.addHeader("Authorization", "bearer good-key");
        MockHttpServletRequest header = get("/api/jobs");
        header.addHeader("X-Api-Key", " good-key ");
        keyFilter(5, 5).doFilter(bearer, new MockHttpServletResponse(), chain);
        keyFilter(5, 5).doFilter(header, new MockHttpServletResponse(), chain);
        assertThat(reached).hasValue(2);
        assertThat(ApiKeyFilter.principal(bearer)).contains(PRINCIPAL);
    }

    @Test
    void anInvalidKeyIsRefusedWithoutSayingWhy() throws Exception {
        MockHttpServletRequest request = get("/api/jobs");
        request.addHeader("Authorization", "Bearer nope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        keyFilter(5, 5).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(reached).hasValue(0);
        assertThat(ApiKeyFilter.principal(request)).isEmpty();
        assertThat(rejected("invalid_key")).isEqualTo(1);
    }

    @Test
    void anEmptyBearerValueCountsAsMissing() {
        MockHttpServletRequest request = get("/api/jobs");
        request.addHeader("Authorization", "Bearer   ");
        assertThat(ApiKeyFilter.credential(request)).isNull();
        request.addHeader("Authorization", "Basic abc");
        assertThat(ApiKeyFilter.credential(request)).isNull();
    }

    @Test
    void aKeyThatIsTooBusyGetsRetryAfter() throws Exception {
        ApiKeyFilter filter = keyFilter(2, 5);
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = get("/api/jobs");
            request.addHeader("X-Api-Key", "good-key");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest third = get("/api/jobs");
        third.addHeader("X-Api-Key", "good-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(third, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(reached).hasValue(2);
        clock.advance(Duration.ofSeconds(2));
        MockHttpServletRequest later = get("/api/jobs");
        later.addHeader("X-Api-Key", "good-key");
        filter.doFilter(later, new MockHttpServletResponse(), chain);
        assertThat(reached).hasValue(3);
    }

    @Test
    void repeatedFailuresFromOneAddressAreThrottled() throws Exception {
        ApiKeyFilter filter = keyFilter(5, 3);
        int refused = 0;
        for (int i = 0; i < 6; i++) {
            MockHttpServletRequest request = get("/api/jobs");
            request.addHeader("X-Api-Key", "guess-" + i);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            if (response.getStatus() == 429) {
                refused++;
            }
        }
        assertThat(refused).isEqualTo(3);
        MockHttpServletRequest other = get("/api/jobs");
        other.setRemoteAddr("10.9.9.9");
        other.addHeader("X-Api-Key", "good-key");
        filter.doFilter(other, new MockHttpServletResponse(), chain);
        assertThat(reached).hasValue(1);
    }

    @Test
    void securityHeadersAreAlwaysSetAndTightenedForTheApi() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter("/api/");
        MockHttpServletResponse api = new MockHttpServletResponse();
        filter.doFilter(get("/api/jobs"), api, chain);
        assertThat(api.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(api.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(api.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(api.getHeader("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(api.getHeader("Strict-Transport-Security")).isNull();
        MockHttpServletResponse other = new MockHttpServletResponse();
        filter.doFilter(get("/actuator/health"), other, chain);
        assertThat(other.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(other.getHeader("Cache-Control")).isNull();
        MockHttpServletRequest secure = get("/api/jobs");
        secure.setSecure(true);
        MockHttpServletResponse https = new MockHttpServletResponse();
        filter.doFilter(secure, https, chain);
        assertThat(https.getHeader("Strict-Transport-Security")).contains("max-age=");
    }

    @Test
    void aDeclaredOversizeBodyIsRejectedBeforeTheHandlerRuns() throws Exception {
        RequestSizeFilter filter = new RequestSizeFilter(100, meters);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/echo");
        request.setContent(new byte[500]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(reached).hasValue(0);
        assertThat(rejected("too_large")).isEqualTo(1);
    }

    @Test
    void anUndeclaredOversizeBodyIsCutOffWhileItIsRead() throws Exception {
        RequestSizeFilter filter = new RequestSizeFilter(100, meters);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/echo") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        request.setContent(new byte[500]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain reading =
                (req, res) -> {
                    reached.incrementAndGet();
                    try (var in = req.getInputStream()) {
                        in.readAllBytes();
                    }
                };
        filter.doFilter(request, response, reading);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(reached).hasValue(1);
    }

    @Test
    void smallBodiesPassThroughUntouched() throws Exception {
        RequestSizeFilter filter = new RequestSizeFilter(100, meters);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/echo");
        request.setContent("hello".getBytes(StandardCharsets.UTF_8));
        StringBuilder seen = new StringBuilder();
        FilterChain reading =
                (req, res) -> {
                    try (var in = req.getInputStream()) {
                        seen.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    int single = req.getInputStream().read();
                    assertThat(single).isEqualTo(-1);
                };
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, reading);
        assertThat(seen.toString()).isEqualTo("hello");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void otherFailuresInsideTheChainAreNotSwallowed() {
        RequestSizeFilter filter = new RequestSizeFilter(100, meters);
        FilterChain failing =
                (req, res) -> {
                    throw new IOException("disk gone");
                };
        assertThrows(
                IOException.class,
                () ->
                        filter.doFilter(
                                new MockHttpServletRequest("POST", "/api/echo"),
                                new MockHttpServletResponse(),
                                failing));
    }
}
