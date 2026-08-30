package dev.rook.agent.loop;

import dev.rook.agent.model.ModelResponse;
import dev.rook.agent.model.ToolCall;
import dev.rook.agent.tools.ToolResult;

public interface AgentListener {

    AgentListener NONE = new AgentListener() {};

    default void modelCalled(int iteration, ModelResponse response, long costMicros) {}

    default void toolCalled(ToolCall call, ToolResult result) {}

    default void testsRun(TestRun run) {}
}
