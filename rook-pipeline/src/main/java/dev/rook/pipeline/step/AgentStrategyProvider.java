package dev.rook.pipeline.step;

import dev.rook.agent.loop.AgentBudget;
import dev.rook.agent.loop.AgentListener;
import dev.rook.agent.loop.AgentLoop;
import dev.rook.agent.loop.FinishTool;
import dev.rook.agent.loop.RunTestsTool;
import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.PriceTable;
import dev.rook.agent.safety.PromptSanitizer;
import dev.rook.agent.strategy.HybridStrategy;
import dev.rook.agent.tools.ApplyEditTool;
import dev.rook.agent.tools.FindReferencesTool;
import dev.rook.agent.tools.ListImpactedTool;
import dev.rook.agent.tools.ReadFileTool;
import dev.rook.agent.tools.ReadMigrationNotesTool;
import dev.rook.agent.tools.SearchSymbolTool;
import dev.rook.agent.tools.ToolRegistry;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;

public class AgentStrategyProvider implements StrategyProvider {

    private static final int PROMPT_CHARS = 8_000;

    private final ModelClient model;
    private final PriceTable prices;
    private final AgentBudget budget;
    private final Function<UUID, SandboxPort> sandboxes;
    private final Clock clock;

    public AgentStrategyProvider(
            ModelClient model,
            PriceTable prices,
            AgentBudget budget,
            Function<UUID, SandboxPort> sandboxes,
            Clock clock) {
        this.model = model;
        this.prices = prices;
        this.budget = budget;
        this.sandboxes = sandboxes;
        this.clock = clock;
    }

    @Override
    public Prepared prepare(UUID jobId) {
        SandboxPort sandbox = sandboxes.apply(jobId);
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
                        clock,
                        budget,
                        PromptSanitizer.standard(PROMPT_CHARS),
                        AgentListener.NONE);
        return new Prepared(
                new HybridStrategy(loop, sandbox), budget.maxCostMicros(), registry::close);
    }
}
