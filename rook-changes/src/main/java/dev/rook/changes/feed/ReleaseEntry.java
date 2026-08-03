package dev.rook.changes.feed;

import java.time.Instant;
import java.util.Objects;

public record ReleaseEntry(
        String version,
        String title,
        Instant publishedAt,
        String body,
        String url,
        boolean prerelease) {

    public ReleaseEntry {
        Objects.requireNonNull(version, "version");
        title = title == null ? version : title;
        body = body == null ? "" : body;
        url = url == null ? "" : url;
    }

    public static String normalizeVersion(String tag) {
        String trimmed = tag.trim();
        if (trimmed.length() > 1
                && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')
                && Character.isDigit(trimmed.charAt(1))) {
            return trimmed.substring(1);
        }
        return trimmed;
    }
}
