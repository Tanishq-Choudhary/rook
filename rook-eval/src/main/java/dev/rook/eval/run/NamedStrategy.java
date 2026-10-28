package dev.rook.eval.run;

import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.strategy.Strategy;
import dev.rook.agent.strategy.StrategyOutcome;
import dev.rook.agent.workspace.Workspace;

record NamedStrategy(String name, Strategy delegate) implements Strategy {

    @Override
    public StrategyOutcome run(AgentTask task, Workspace workspace) {
        return delegate.run(task, workspace);
    }
}
