package dev.rook.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.github.webhook.SignatureVerifier;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScenariosTest {

    private static final String SECRET = "a-secret-of-sufficient-length";

    @Test
    void theSignatureMatchesWhatTheServerChecks() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        String signature = Scenarios.sign(SECRET, body);

        assertThat(new SignatureVerifier(SECRET).verify(body, signature)).isTrue();
        assertThat(new SignatureVerifier(SECRET).sign(body)).isEqualTo(signature);
        assertThat(new SignatureVerifier("another-secret-long-enough").verify(body, signature))
                .isFalse();
    }

    @Test
    void apiReadSendsTheKeyAsABearerToken() {
        Scenario scenario = Scenarios.apiRead("http://host:8080/", "key-1");

        HttpRequest request = scenario.request(1);

        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().toString()).isEqualTo("http://host:8080/api/v1/jobs?size=25");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer key-1");
        assertThat(scenario.accepted(200)).isTrue();
        assertThat(scenario.accepted(401)).isFalse();
        assertThat(scenario.name()).isEqualTo("api-read");
    }

    @Test
    void everyWebhookDeliveryHasItsOwnIdAndAValidSignature() {
        Scenario scenario = Scenarios.webhook("http://host:8080", SECRET, "run1");

        HttpRequest first = scenario.request(1);
        HttpRequest second = scenario.request(2);

        assertThat(first.method()).isEqualTo("POST");
        assertThat(first.uri().getPath()).isEqualTo("/webhooks/github");
        assertThat(first.headers().firstValue("X-GitHub-Delivery")).contains("run1-1");
        assertThat(second.headers().firstValue("X-GitHub-Delivery")).contains("run1-2");
        assertThat(first.headers().firstValue("X-GitHub-Event")).contains("ping");
        assertThat(first.headers().firstValue("X-Hub-Signature-256").orElseThrow())
                .startsWith("sha256=")
                .isNotEqualTo(second.headers().firstValue("X-Hub-Signature-256").orElseThrow());
        assertThat(scenario.accepted(200)).isTrue();
        assertThat(scenario.accepted(500)).isFalse();
        assertThat(scenario.name()).isEqualTo("webhook");
    }
}
