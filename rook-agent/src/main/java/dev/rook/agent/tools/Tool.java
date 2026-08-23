package dev.rook.agent.tools;

import dev.rook.agent.model.ToolDefinition;
import tools.jackson.databind.JsonNode;

public interface Tool {

    ToolDefinition definition();

    ToolResult execute(JsonNode arguments, ToolContext context) throws Exception;
}
