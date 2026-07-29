package dev.rook.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rook")
public record RookProperties(Role role) {

    public RookProperties {
        role = role == null ? Role.ALL : role;
    }

    public boolean runsApi() {
        return role == Role.API || role == Role.ALL;
    }

    public boolean runsWorker() {
        return role == Role.WORKER || role == Role.ALL;
    }

    public enum Role {
        API,
        WORKER,
        ALL
    }
}
