package dev.rook.server.control;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.control.auth.ApiKeys;
import dev.rook.control.auth.Scope;
import dev.rook.core.id.Ids;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AutoConfigureMetrics
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "rook.workflow.enabled=false",
            "rook.control.bootstrap-key=bootstrap-key-with-more-than-thirty-two-chars",
            "rook.control.rate-burst=4",
            "rook.control.rate-per-second=0.1",
            "rook.control.failure-burst=3",
            "rook.control.max-request-bytes=200"
        })
@Testcontainers
class ControlWiringIT {

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @LocalServerPort int port;

    @Autowired ApiKeys keys;
    @Autowired JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String key, String body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String issue(String name) {
        UUID installation = Ids.newId();
        jdbc.sql("insert into installation (id, account_login) values (:id, :login)")
                .param("id", installation)
                .param("login", name)
                .update();
        return keys.issue(installation, name, EnumSet.of(Scope.READ, Scope.WRITE)).key();
    }

    @Test
    void healthStaysOpenAndEveryResponseCarriesSecurityHeaders() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/health", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    }

    @Test
    void theApiRefusesRequestsWithoutAValidKey() throws Exception {
        HttpResponse<String> missing = send("GET", "/api/probe", null, null);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(missing.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(missing.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(missing.body()).contains("urn:rook:error:unauthorized");
        HttpResponse<String> wrong =
                send("GET", "/api/probe", "rk_aaaaaaaaaa_" + "b".repeat(43), null);
        assertThat(wrong.statusCode()).isEqualTo(401);
    }

    @Test
    void aValidKeyReachesTheHandlerAsItsPrincipal() throws Exception {
        String key = issue("acme-valid");
        HttpResponse<String> response = send("GET", "/api/probe", key, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("acme-valid:2");
        assertThat(response.headers().firstValue("X-RateLimit-Remaining")).isPresent();
    }

    @Test
    void theBootstrapKeyFromConfigurationWorksAsAnAdmin() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/probe", "bootstrap-key-with-more-than-thirty-two-chars", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("bootstrap:1");
    }

    @Test
    void aRevokedKeyStopsWorkingImmediately() throws Exception {
        UUID installation = Ids.newId();
        jdbc.sql("insert into installation (id, account_login) values (:id, 'acme-revoked')")
                .param("id", installation)
                .update();
        ApiKeys.Issued issued = keys.issue(installation, "temp", EnumSet.of(Scope.READ));
        assertThat(send("GET", "/api/probe", issued.key(), null).statusCode()).isEqualTo(200);
        keys.revoke(issued.id());
        assertThat(send("GET", "/api/probe", issued.key(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void aBusyKeyIsSlowedDownWithRetryAfter() throws Exception {
        String key = issue("acme-busy");
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 8; i++) {
            HttpResponse<String> response = send("GET", "/api/probe", key, null);
            if (response.statusCode() == 429) {
                limited++;
                retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            }
        }
        assertThat(limited).isGreaterThanOrEqualTo(3);
        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isPositive();
    }

    @Test
    void oversizeBodiesAreRefusedAndSmallOnesAreRead() throws Exception {
        String key = issue("acme-body");
        assertThat(send("POST", "/api/probe", key, "x".repeat(150)).body()).isEqualTo("150");
        HttpResponse<String> big = send("POST", "/api/probe", key, "x".repeat(5_000));
        assertThat(big.statusCode()).isEqualTo(413);
        assertThat(big.body()).contains("payload_too_large");
    }

    @Test
    void failuresAreCountedInTheMetrics() throws Exception {
        send("GET", "/api/probe", null, null);
        HttpResponse<String> metrics = send("GET", "/actuator/prometheus", null, null);
        assertThat(metrics.body()).contains("rook_http_rejected_total");
    }
}
