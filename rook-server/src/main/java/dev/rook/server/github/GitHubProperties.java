package dev.rook.server.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rook.github")
public record GitHubProperties(
        Long appId,
        String privateKey,
        String privateKeyPath,
        String webhookSecret,
        String apiUrl,
        String dashboardUrl) {

    public GitHubProperties {
        apiUrl = apiUrl == null || apiUrl.isBlank() ? "https://api.github.com" : apiUrl;
        privateKey = blankToNull(privateKey);
        privateKeyPath = blankToNull(privateKeyPath);
        webhookSecret = blankToNull(webhookSecret);
        dashboardUrl = blankToNull(dashboardUrl);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
