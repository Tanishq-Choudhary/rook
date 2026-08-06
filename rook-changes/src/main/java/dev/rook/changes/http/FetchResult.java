package dev.rook.changes.http;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

public record FetchResult(int status, byte[] body, Map<String, String> headers, URI finalUri) {

    public FetchResult {
        headers = Map.copyOf(headers);
    }

    public boolean notModified() {
        return status == 304;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    public Optional<String> etag() {
        return Optional.ofNullable(headers.get("etag"));
    }

    public String contentType() {
        return headers.getOrDefault("content-type", "");
    }

    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
