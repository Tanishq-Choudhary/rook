package dev.rook.agent.loop;

import dev.rook.agent.model.ToolDefinition;
import dev.rook.agent.tools.Schemas;
import dev.rook.agent.tools.Tool;
import dev.rook.agent.tools.ToolContext;
import dev.rook.agent.tools.ToolResult;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class FinishTool implements Tool {

    public static final String NAME = "finish";

    private final ToolDefinition definition =
            new ToolDefinition(
                    NAME,
                    "End the task. Call it once the migration is complete or when you cannot make"
                            + " further progress. List anything you could not resolve and any risk a"
                            + " reviewer should look at. The tests are run before the result is"
                            + " accepted.",
                    Schemas.object(
                            Map.of(
                                    "summary", Schemas.string("what changed and why", 1, 4_000),
                                    "confidence",
                                            Schemas.number(
                                                    "how sure you are that the change is correct",
                                                    0,
                                                    1),
                                    "unresolved",
                                            Schemas.array(
                                                    "places or problems you did not resolve",
                                                    Schemas.string("one item", 1, 500),
                                                    20),
                                    "risks",
                                            Schemas.array(
                                                    "behaviour changes a reviewer should check",
                                                    Schemas.string("one item", 1, 500),
                                                    20)),
                            List.of("summary", "confidence")));

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        return ToolResult.ok("finish received");
    }
}
