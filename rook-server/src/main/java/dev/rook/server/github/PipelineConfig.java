package dev.rook.server.github;

import dev.rook.agent.loop.AgentBudget;
import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.ModelPrice;
import dev.rook.agent.model.PriceTable;
import dev.rook.agent.model.RetryingModelClient;
import dev.rook.agent.model.http.AnthropicClient;
import dev.rook.agent.model.http.ApiKey;
import dev.rook.agent.model.http.OpenAiCompatibleClient;
import dev.rook.agent.strategy.CodemodOnlyStrategy;
import dev.rook.changes.store.ChangeIngestion;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.codeintel.project.RookConfig;
import dev.rook.control.audit.AuditLog;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.store.ApprovalStore;
import dev.rook.control.store.PolicyStore;
import dev.rook.core.error.RookException;
import dev.rook.github.api.GitHubClient;
import dev.rook.github.publish.PrPublisher;
import dev.rook.pipeline.access.RepositoryAccess;
import dev.rook.pipeline.step.AgentStrategyProvider;
import dev.rook.pipeline.step.IndexingHandler;
import dev.rook.pipeline.step.ProposingHandler;
import dev.rook.pipeline.step.PublishingHandler;
import dev.rook.pipeline.step.StrategyProvider;
import dev.rook.pipeline.step.VerifyingHandler;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.pipeline.verify.DockerPatchVerifier;
import dev.rook.pipeline.verify.PatchVerifier;
import dev.rook.verify.gate.Gates;
import dev.rook.verify.run.DockerSandboxPort;
import dev.rook.verify.run.PhaseRunner;
import dev.rook.verify.run.Verifier;
import dev.rook.verify.sandbox.DockerConfig;
import dev.rook.verify.sandbox.DockerSandboxRunner;
import dev.rook.workflow.repo.RepositoryStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${rook.github.app-id:}'.isEmpty() and ${rook.workflow.enabled:true}")
public class PipelineConfig {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression(
            "!'${rook.github.app-id:}'.isEmpty() and ${rook.workflow.enabled:true}"
                    + " and !'${rook.role:all}'.equalsIgnoreCase('api')")
    static class Workers {

        @Bean
        PhaseRunner phaseRunner(Clock clock) {
            return new PhaseRunner(new DockerSandboxRunner(DockerConfig.standard(), clock));
        }

        @Bean
        PatchVerifier patchVerifier(PhaseRunner phases, PipelineProperties properties) {
            return new DockerPatchVerifier(
                    new Verifier(phases, new Gates()),
                    Path.of(properties.cacheDir()),
                    properties.mavenImage(),
                    properties.flakeReruns());
        }

        @Bean
        @ConditionalOnExpression("!'${rook.pipeline.model-provider:none}'.equalsIgnoreCase('none')")
        ModelClient modelClient(PipelineProperties properties) {
            if (properties.modelName() == null || properties.modelApiKey() == null) {
                throw RookException.invalid("a model name and api key are required");
            }
            ApiKey key = new ApiKey(properties.modelApiKey());
            ModelClient raw =
                    switch (properties.modelProvider().toLowerCase()) {
                        case "anthropic" ->
                                new AnthropicClient(
                                        properties.modelBaseUrl() == null
                                                ? AnthropicClient.DEFAULT_BASE
                                                : properties.modelBaseUrl(),
                                        key,
                                        properties.modelName(),
                                        Duration.ofMinutes(2));
                        case "openai" -> {
                            if (properties.modelBaseUrl() == null) {
                                throw RookException.invalid(
                                        "rook.pipeline.model-base-url is required");
                            }
                            yield new OpenAiCompatibleClient(
                                    properties.modelBaseUrl(),
                                    key,
                                    properties.modelName(),
                                    Duration.ofMinutes(2));
                        }
                        default ->
                                throw RookException.invalid(
                                        "unknown model provider " + properties.modelProvider());
                    };
            return new RetryingModelClient(
                    raw,
                    3,
                    Duration.ofMillis(500),
                    Duration.ofSeconds(8),
                    duration -> Thread.sleep(duration),
                    RandomGenerator.of("L64X128MixRandom"));
        }

        @Bean
        StrategyProvider strategyProvider(
                Optional<ModelClient> model,
                PhaseRunner phases,
                PipelineProperties properties,
                Clock clock) {
            if (model.isEmpty()) {
                return jobId -> StrategyProvider.Prepared.free(new CodemodOnlyStrategy());
            }
            if (properties.modelInputMicrosPerMillion() == null
                    || properties.modelOutputMicrosPerMillion() == null) {
                throw RookException.invalid("model prices are required when a model is configured");
            }
            PriceTable prices =
                    PriceTable.of(
                            Map.of(
                                    properties.modelName(),
                                    new ModelPrice(
                                            properties.modelInputMicrosPerMillion(),
                                            properties.modelCachedInputMicrosPerMillion() == null
                                                    ? properties.modelInputMicrosPerMillion()
                                                    : properties.modelCachedInputMicrosPerMillion(),
                                            properties.modelOutputMicrosPerMillion())));
            AgentBudget budget =
                    new AgentBudget(
                            12,
                            400_000,
                            properties.agentBudgetMicros(),
                            Duration.ofMinutes(10),
                            3,
                            8,
                            60);
            return new AgentStrategyProvider(
                    model.get(),
                    prices,
                    budget,
                    jobId -> sandbox(phases, properties, jobId),
                    clock);
        }

        private static SandboxPort sandbox(
                PhaseRunner phases, PipelineProperties properties, UUID jobId) {
            return new DockerSandboxPort(
                    phases,
                    new RookConfig.Verify(null, null),
                    Path.of(properties.cacheDir()).resolve("agent-" + jobId),
                    properties.mavenImage());
        }

        @Bean
        IndexingHandler indexingHandler(
                ChangeIngestion ingestion,
                RepositoryAccess access,
                ArtifactStore artifacts,
                AuditLog audit,
                PipelineProperties properties) {
            return new IndexingHandler(
                    ingestion::load,
                    access,
                    artifacts,
                    audit,
                    Path.of(properties.workDir()),
                    RepoAnalyzer.Options.defaults());
        }

        @Bean
        ProposingHandler proposingHandler(
                ChangeIngestion ingestion,
                RepositoryAccess access,
                RepositoryStore repositories,
                PolicyStore policies,
                ArtifactStore artifacts,
                BudgetService budgets,
                AuditLog audit,
                StrategyProvider strategies,
                PipelineProperties properties) {
            return new ProposingHandler(
                    ingestion::load,
                    access,
                    repositories,
                    policies,
                    artifacts,
                    budgets,
                    audit,
                    strategies,
                    Path.of(properties.workDir()));
        }

        @Bean
        VerifyingHandler verifyingHandler(
                RepositoryAccess access,
                RepositoryStore repositories,
                PolicyStore policies,
                ApprovalStore approvals,
                ArtifactStore artifacts,
                PatchVerifier verifier,
                AuditLog audit,
                PipelineProperties properties) {
            return new VerifyingHandler(
                    access,
                    repositories,
                    policies,
                    approvals,
                    artifacts,
                    verifier,
                    audit,
                    Path.of(properties.workDir()));
        }

        @Bean
        PublishingHandler publishingHandler(
                ChangeIngestion ingestion,
                RepositoryStore repositories,
                ArtifactStore artifacts,
                PolicyStore policies,
                GitHubClient client,
                PrPublisher publisher,
                AuditLog audit,
                GitHubProperties github) {
            return new PublishingHandler(
                    ingestion::load,
                    repositories,
                    artifacts,
                    policies,
                    client,
                    publisher,
                    audit,
                    github.dashboardUrl());
        }
    }
}
