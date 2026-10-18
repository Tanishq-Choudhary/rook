package dev.rook.eval.run;

import dev.rook.agent.loop.AgentBudget;
import dev.rook.agent.loop.AgentListener;
import dev.rook.agent.loop.AgentLoop;
import dev.rook.agent.loop.FinishTool;
import dev.rook.agent.loop.RunTestsTool;
import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.ModelPrice;
import dev.rook.agent.model.PriceTable;
import dev.rook.agent.model.RetryingModelClient;
import dev.rook.agent.model.http.ApiKey;
import dev.rook.agent.model.http.OpenAiCompatibleClient;
import dev.rook.agent.safety.PromptSanitizer;
import dev.rook.agent.strategy.HybridStrategy;
import dev.rook.agent.strategy.LlmOnlyStrategy;
import dev.rook.agent.strategy.Strategy;
import dev.rook.agent.tools.ApplyEditTool;
import dev.rook.agent.tools.FindReferencesTool;
import dev.rook.agent.tools.ListImpactedTool;
import dev.rook.agent.tools.ReadFileTool;
import dev.rook.agent.tools.ReadMigrationNotesTool;
import dev.rook.agent.tools.SearchSymbolTool;
import dev.rook.agent.tools.ToolRegistry;
import dev.rook.core.random.SharedRandom;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

public final class ModelStrategies {

    private static final int PROMPT_CHARS = 8_000;

    private final WatchedModelClient model;
    private final PriceTable prices;
    private final AgentBudget budget;
    private final Path traces;

    public ModelStrategies(
            String baseUrl,
            String apiKey,
            String modelName,
            Duration timeout,
            Duration gap,
            int iterations,
            Path traces) {
        ModelClient raw =
                new OpenAiCompatibleClient(baseUrl, new ApiKey(apiKey), modelName, timeout);
        this.model =
                new WatchedModelClient(
                        new RetryingModelClient(
                                new PacedModelClient(raw, gap),
                                6,
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(60),
                                duration -> Thread.sleep(duration),
                                SharedRandom.get()));
        this.traces = traces;
        this.prices = PriceTable.of(Map.of(modelName, new ModelPrice(0, 0, 0)));
        this.budget =
                new AgentBudget(
                        iterations,
                        400_000,
                        1_000_000,
                        Duration.ofMinutes(8),
                        2,
                        6,
                        iterations * 4);
    }

    public long[] takeUsage() {
        return model.takeUsage();
    }

    public String takeFailure() {
        return model.takeFailure();
    }

    public Strategy llmOnly() {
        return new TracingStrategy(
                new LlmOnlyStrategy(model, prices, budget, PromptSanitizer.standard(PROMPT_CHARS)),
                traces);
    }

    public Strategy agent(SandboxPort sandbox) {
        ToolRegistry registry =
                new ToolRegistry(ToolRegistry.Limits.standard(), ToolRegistry.Listener.NOOP)
                        .register(new ReadFileTool())
                        .register(new ApplyEditTool())
                        .register(new ListImpactedTool())
                        .register(new SearchSymbolTool())
                        .register(new FindReferencesTool())
                        .register(new ReadMigrationNotesTool())
                        .register(new RunTestsTool(sandbox, AgentListener.NONE))
                        .register(new FinishTool());
        AgentLoop loop =
                new AgentLoop(
                        model,
                        prices,
                        registry,
                        sandbox,
                        Clock.systemUTC(),
                        budget,
                        PromptSanitizer.standard(PROMPT_CHARS),
                        AgentListener.NONE);
        return new TracingStrategy(new HybridStrategy(loop, sandbox), traces);
    }
}
