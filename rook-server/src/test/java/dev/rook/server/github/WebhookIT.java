package dev.rook.server.github;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.json.Json;
import dev.rook.github.webhook.SignatureVerifier;
import dev.rook.pipeline.step.ApprovalService;
import dev.rook.testkit.FakeGitHub;
import dev.rook.testkit.TestClock;
import dev.rook.workflow.StepHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@AutoConfigureMetrics
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rook.role=api", "rook.changes.scheduler-enabled=false"})
@Testcontainers
class WebhookIT {

    private static final String SECRET = "a-webhook-secret-with-enough-length";

    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static final FakeGitHub GITHUB = new FakeGitHub(TestClock.at("2026-10-01T10:00:00Z"));

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String body =
                Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        registry.add("rook.github.app-id", () -> "9");
        registry.add(
                "rook.github.private-key",
                () -> "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
        registry.add("rook.github.webhook-secret", () -> SECRET);
        registry.add("rook.github.api-url", GITHUB::baseUrl);
        registry.add("rook.workflow.enabled", () -> "true");
    }

    @AfterAll
    static void stop() {
        GITHUB.close();
    }

    @LocalServerPort int port;

    @Autowired JdbcClient jdbc;
    @Autowired ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SignatureVerifier signer = new SignatureVerifier(SECRET);

    private HttpResponse<String> deliver(String id, String event, String body, String signature)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/github"))
                        .header("X-GitHub-Delivery", id)
                        .header("X-GitHub-Event", event)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signed(String id, String event, String body) throws Exception {
        return deliver(id, event, body, signer.sign(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String installationCreated(long id, String login, String repoName) {
        return Json.write(
                Map.of(
                        "action",
                        "created",
                        "installation",
                        Map.of("id", id, "account", Map.of("login", login)),
                        "repositories",
                        List.of(Map.of("id", id * 10, "full_name", login + "/" + repoName))));
    }

    @Test
    void aSignedInstallationEventCreatesTheAccountAndItsRepositories() throws Exception {
        HttpResponse<String> response =
                signed("wh-1", "installation", installationCreated(7001, "acme", "shop"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("HANDLED");
        assertThat(
                        jdbc.sql(
                                        "select account_login from installation where github_installation_id = 7001")
                                .query(String.class)
                                .single())
                .isEqualTo("acme");
        assertThat(
                        jdbc.sql("select count(*) from repository where full_name = 'acme/shop'")
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void aRedeliveredEventIsAcknowledgedAndIgnored() throws Exception {
        String body = installationCreated(7002, "globex", "app");
        assertThat(signed("wh-2", "installation", body).body()).contains("HANDLED");
        HttpResponse<String> again = signed("wh-2", "installation", body);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).contains("DUPLICATE");
    }

    @Test
    void aWrongOrMissingSignatureIsRefusedAndNothingChanges() throws Exception {
        String body = installationCreated(7003, "initech", "web");
        HttpResponse<String> forged =
                deliver("wh-3", "installation", body, "sha256=" + "0".repeat(64));
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(forged.headers().firstValue("Content-Type").orElse("")).contains("json");
        assertThat(forged.body()).doesNotContain("Exception");
        assertThat(deliver("wh-4", "installation", body, null).statusCode()).isEqualTo(401);
        assertThat(
                        jdbc.sql(
                                        "select count(*) from installation where github_installation_id = 7003")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void aSignedButMalformedPayloadIsABadRequest() throws Exception {
        HttpResponse<String> response = signed("wh-5", "installation", "not json");
        assertThat(response.statusCode()).isEqualTo(400);
        HttpResponse<String> missing = signed("wh-6", "installation", "{}");
        assertThat(missing.statusCode()).isEqualTo(400);
    }

    @Test
    void webhooksMayBeLargerThanApiRequests() throws Exception {
        String padding = "x".repeat(2 * 1024 * 1024);
        String body = Json.write(Map.of("zen", padding));
        HttpResponse<String> response = signed("wh-7", "ping", body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("IGNORED");
    }

    @Test
    void theApiRoleServesApprovalsButRunsNoStepHandlers() {
        assertThat(context.getBeansOfType(ApprovalService.class)).hasSize(1);
        assertThat(context.getBeansOfType(StepHandler.class)).isEmpty();
    }

    @Test
    void theWebhookEndpointIsNotBehindTheApiKeyFilter() throws Exception {
        HttpResponse<String> response = signed("wh-8", "ping", "{\"zen\":\"keep it simple\"}");
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
