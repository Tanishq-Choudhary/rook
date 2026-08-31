package dev.rook.agent.strategy;

import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.workspace.Workspace;

public interface Strategy {

    String name();

    StrategyOutcome run(AgentTask task, Workspace workspace);
}
