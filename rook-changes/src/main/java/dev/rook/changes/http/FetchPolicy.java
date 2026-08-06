package dev.rook.changes.http;

import java.time.Duration;
import java.util.Set;

public record FetchPolicy(
        Set<String> allowedSchemes,
        boolean allowPrivateAddresses,
        long maxBytes,
        Duration connectTimeout,
        Duration readTimeout,
        Duration totalTimeout,
        int maxRedirects) {

    public FetchPolicy {
        allowedSchemes = Set.copyOf(allowedSchemes);
        if (maxBytes <= 0 || maxRedirects < 0) {
            throw new IllegalArgumentException("invalid fetch policy");
        }
    }

    public static FetchPolicy strict() {
        return new FetchPolicy(
                Set.of("https"),
                false,
                25L * 1024 * 1024,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                3);
    }

    public FetchPolicy withMaxBytes(long bytes) {
        return new FetchPolicy(
                allowedSchemes,
                allowPrivateAddresses,
                bytes,
                connectTimeout,
                readTimeout,
                totalTimeout,
                maxRedirects);
    }

    public FetchPolicy withTimeouts(Duration connect, Duration read, Duration total) {
        return new FetchPolicy(
                allowedSchemes,
                allowPrivateAddresses,
                maxBytes,
                connect,
                read,
                total,
                maxRedirects);
    }

    public FetchPolicy withMaxRedirects(int redirects) {
        return new FetchPolicy(
                allowedSchemes,
                allowPrivateAddresses,
                maxBytes,
                connectTimeout,
                readTimeout,
                totalTimeout,
                redirects);
    }

    public FetchPolicy allowingLocalHttp() {
        return new FetchPolicy(
                Set.of("http", "https"),
                true,
                maxBytes,
                connectTimeout,
                readTimeout,
                totalTimeout,
                maxRedirects);
    }
}
