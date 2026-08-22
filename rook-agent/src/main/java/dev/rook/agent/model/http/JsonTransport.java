package dev.rook.agent.model.http;

import dev.rook.agent.model.ModelException;
import dev.rook.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;

final class JsonTransport {

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int ERROR_EXCERPT = 300;

    private final HttpClient client;
    private final Duration timeout;

    JsonTransport(HttpClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    JsonNode post(URI uri, Map<String, String> headers, String body) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new ModelException("model request timed out", true, e);
        } catch (IOException e) {
            throw new ModelException(
                    "model request failed: " + e.getClass().getSimpleName(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("model request interrupted", false, e);
        }
        String text = read(response);
        int status = response.statusCode();
        if (status / 100 != 2) {
            boolean retryable = status == 408 || status == 429 || status >= 500;
            throw new ModelException(
                    "model api answered " + status + ": " + excerpt(text), retryable);
        }
        try {
            return Json.tree(text);
        } catch (RuntimeException e) {
            throw new ModelException("model api returned unreadable json", false, e);
        }
    }

    private static String read(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new ModelException("model api response is too large", false);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ModelException("model response could not be read", true, e);
        }
    }

    private static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= ERROR_EXCERPT ? flat : flat.substring(0, ERROR_EXCERPT);
    }
}
