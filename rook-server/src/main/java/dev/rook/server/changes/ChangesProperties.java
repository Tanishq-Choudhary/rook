package dev.rook.server.changes;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rook.changes")
public record ChangesProperties(
        Boolean schedulerEnabled,
        Duration schedulerDelay,
        Integer batchSize,
        Boolean allowLocalHttp,
        String githubApiBase,
        Long maxArtifactBytes) {

    public ChangesProperties {
        schedulerEnabled = schedulerEnabled == null || schedulerEnabled;
        schedulerDelay = schedulerDelay == null ? Duration.ofSeconds(15) : schedulerDelay;
        batchSize = batchSize == null ? 10 : batchSize;
        allowLocalHttp = allowLocalHttp != null && allowLocalHttp;
        githubApiBase = githubApiBase == null ? "https://api.github.com" : githubApiBase;
        maxArtifactBytes = maxArtifactBytes == null ? 100L * 1024 * 1024 : maxArtifactBytes;
    }
}
