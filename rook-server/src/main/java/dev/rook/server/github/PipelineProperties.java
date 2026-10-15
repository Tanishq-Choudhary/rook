package dev.rook.server.github;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rook.pipeline")
public record PipelineProperties(
        String workDir,
        String cacheDir,
        String mavenImage,
        Integer flakeReruns,
        String modelProvider,
        String modelName,
        String modelApiKey,
        String modelBaseUrl,
        Long modelInputMicrosPerMillion,
        Long modelCachedInputMicrosPerMillion,
        Long modelOutputMicrosPerMillion,
        Long agentBudgetMicros,
        Duration reapEvery,
        Duration reapAfter) {

    public PipelineProperties {
        workDir = workDir == null || workDir.isBlank() ? defaultDir("work") : workDir;
        cacheDir = cacheDir == null || cacheDir.isBlank() ? defaultDir("cache") : cacheDir;
        mavenImage =
                mavenImage == null || mavenImage.isBlank()
                        ? "maven:3.9-eclipse-temurin-21"
                        : mavenImage;
        flakeReruns = flakeReruns == null ? 2 : flakeReruns;
        modelProvider = modelProvider == null || modelProvider.isBlank() ? "none" : modelProvider;
        agentBudgetMicros = agentBudgetMicros == null ? 2_000_000L : agentBudgetMicros;
        reapEvery = reapEvery == null ? Duration.ofMinutes(5) : reapEvery;
        reapAfter = reapAfter == null ? Duration.ofMinutes(30) : reapAfter;
        if (reapEvery.isZero() || reapEvery.isNegative() || reapAfter.isNegative()) {
            throw new IllegalArgumentException("the sweep interval must be positive");
        }
        if (flakeReruns < 0 || agentBudgetMicros < 0) {
            throw new IllegalArgumentException("reruns and budgets must not be negative");
        }
    }

    public boolean modelEnabled() {
        return !modelProvider.equalsIgnoreCase("none");
    }

    private static String defaultDir(String name) {
        return Path.of(System.getProperty("java.io.tmpdir"), "rook", name).toString();
    }
}
