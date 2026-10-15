package dev.rook.loadtest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Scenarios {

    private Scenarios() {}

    public static Scenario apiRead(String base, String key) {
        URI uri = URI.create(trim(base) + "/api/v1/jobs?size=25");
        return new Scenario() {
            @Override
            public String name() {
                return "api-read";
            }

            @Override
            public HttpRequest request(long sequence) {
                return HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer " + key)
                        .GET()
                        .build();
            }

            @Override
            public boolean accepted(int status) {
                return status == 200;
            }
        };
    }

    public static Scenario webhook(String base, String secret, String run) {
        URI uri = URI.create(trim(base) + "/webhooks/github");
        return new Scenario() {
            @Override
            public String name() {
                return "webhook";
            }

            @Override
            public HttpRequest request(long sequence) {
                byte[] body =
                        ("{\"zen\":\"load " + sequence + "\",\"hook_id\":" + sequence + "}")
                                .getBytes(StandardCharsets.UTF_8);
                return HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("X-GitHub-Event", "ping")
                        .header("X-GitHub-Delivery", run + "-" + sequence)
                        .header("X-Hub-Signature-256", sign(secret, body))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
            }

            @Override
            public boolean accepted(int status) {
                return status == 200;
            }
        };
    }

    static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String trim(String base) {
        return base.replaceAll("/+$", "");
    }
}
