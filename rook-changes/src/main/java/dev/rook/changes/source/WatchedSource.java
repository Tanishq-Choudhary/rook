package dev.rook.changes.source;

import dev.rook.core.change.SourceKind;
import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

public record WatchedSource(
        String id,
        SourceKind kind,
        String displayName,
        String location,
        JsonNode config,
        String etag,
        String lastVersion,
        Instant lastCheckedAt,
        String lastError,
        int pollIntervalSeconds,
        boolean enabled) {

    public WatchedSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        displayName = displayName == null ? id : displayName;
        config = config == null ? JsonNodeFactory.instance.objectNode() : config;
        if (pollIntervalSeconds < 30) {
            throw new IllegalArgumentException("poll interval must be at least 30 seconds");
        }
    }

    public String configText(String key) {
        JsonNode value = config.path(key);
        return value.isString() ? value.asString() : null;
    }

    public boolean configFlag(String key) {
        return config.path(key).asBoolean(false);
    }

    public WatchedSource withState(String newEtag, String newLastVersion) {
        return new WatchedSource(
                id,
                kind,
                displayName,
                location,
                config,
                newEtag,
                newLastVersion,
                lastCheckedAt,
                lastError,
                pollIntervalSeconds,
                enabled);
    }
}
