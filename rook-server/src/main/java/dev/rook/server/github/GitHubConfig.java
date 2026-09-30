package dev.rook.server.github;

import dev.rook.changes.store.SourceRepository;
import dev.rook.core.error.RookException;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.auth.AppJwt;
import dev.rook.github.auth.InstallationTokens;
import dev.rook.github.auth.PrivateKeys;
import dev.rook.github.publish.PrPublisher;
import dev.rook.github.sync.RepositorySynchronizer;
import dev.rook.github.webhook.DeliveryLog;
import dev.rook.github.webhook.PullRequests;
import dev.rook.github.webhook.SignatureVerifier;
import dev.rook.github.webhook.WebhookProcessor;
import dev.rook.pipeline.access.GitHubRepositoryAccess;
import dev.rook.pipeline.access.RepositoryAccess;
import dev.rook.workflow.repo.RepositoryStore;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GitHubProperties.class, PipelineProperties.class})
@ConditionalOnExpression("!'${rook.github.app-id:}'.isEmpty() and ${rook.workflow.enabled:true}")
public class GitHubConfig {

    record Gateway(GitHubClient client, InstallationTokens tokens) {}

    @Bean
    Gateway githubGateway(GitHubProperties properties, Clock clock) {
        AtomicReference<GitHubClient> holder = new AtomicReference<>();
        AppJwt jwt =
                new AppJwt(properties.appId(), PrivateKeys.parseRsa(privateKey(properties)), clock);
        InstallationTokens tokens =
                new InstallationTokens(
                        (appJwt, installation) -> holder.get().issue(appJwt, installation),
                        jwt,
                        clock);
        GitHubClient client =
                new GitHubClient(
                        properties.apiUrl(),
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                        tokens,
                        Thread::sleep,
                        clock,
                        Duration.ofSeconds(30));
        holder.set(client);
        return new Gateway(client, tokens);
    }

    @Bean
    GitHubClient githubClient(Gateway gateway) {
        return gateway.client();
    }

    @Bean
    InstallationTokens installationTokens(Gateway gateway) {
        return gateway.tokens();
    }

    @Bean
    DeliveryLog deliveryLog(JdbcClient jdbc, Clock clock) {
        return new DeliveryLog(jdbc, clock);
    }

    @Bean
    PullRequests pullRequests(JdbcClient jdbc, Clock clock) {
        return new PullRequests(jdbc, clock);
    }

    @Bean
    RepositoryAccess repositoryAccess(
            RepositoryStore repositories, GitHubClient client, InstallationTokens tokens) {
        return new GitHubRepositoryAccess(repositories, client, tokens);
    }

    @Bean
    PrPublisher prPublisher(
            GitHubClient client,
            InstallationTokens tokens,
            PullRequests pullRequests,
            PipelineProperties pipeline) {
        return new PrPublisher(client, tokens, pullRequests, Path.of(pipeline.workDir()));
    }

    @Bean
    RepositorySynchronizer repositorySynchronizer(
            GitHubClient client,
            InstallationTokens tokens,
            RepositoryStore repositories,
            SourceRepository sources,
            PipelineProperties pipeline) {
        return new RepositorySynchronizer(
                client, tokens, repositories, sources, Path.of(pipeline.workDir()));
    }

    @Bean
    WebhookProcessor webhookProcessor(
            RepositoryStore repositories,
            DeliveryLog deliveries,
            PullRequests pullRequests,
            RepositorySynchronizer synchronizer) {
        return new WebhookProcessor(repositories, deliveries, pullRequests, synchronizer);
    }

    @Bean
    @ConditionalOnExpression("!'${rook.github.webhook-secret:}'.isEmpty()")
    WebhookController webhookController(GitHubProperties properties, WebhookProcessor processor) {
        return new WebhookController(new SignatureVerifier(properties.webhookSecret()), processor);
    }

    private static String privateKey(GitHubProperties properties) {
        if (properties.privateKey() != null) {
            return properties.privateKey();
        }
        if (properties.privateKeyPath() == null) {
            throw RookException.invalid("rook.github.private-key or private-key-path is required");
        }
        try {
            return Files.readString(Path.of(properties.privateKeyPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw RookException.invalid("the github private key file cannot be read", e);
        }
    }
}
