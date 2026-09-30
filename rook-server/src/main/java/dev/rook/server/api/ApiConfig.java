package dev.rook.server.api;

import dev.rook.github.webhook.PullRequests;
import dev.rook.pipeline.store.ArtifactStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class ApiConfig {

    @Bean
    ArtifactStore artifactStore(JdbcClient jdbc) {
        return new ArtifactStore(jdbc);
    }

    @Bean
    PullRequests pullRequests(JdbcClient jdbc, Clock clock) {
        return new PullRequests(jdbc, clock);
    }
}
