package dev.rook.agent.tools;

import dev.rook.agent.model.ToolDefinition;
import dev.rook.agent.workspace.Workspace;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class ApplyEditTool implements Tool {

    private final ToolDefinition definition =
            new ToolDefinition(
                    "apply_edit",
                    "Replace one exact piece of text in a file. The search text must occur exactly"
                            + " once, so include enough surrounding lines to make it unique. Do not"
                            + " include line numbers or the untrusted-content markers in the search"
                            + " text.",
                    Schemas.object(
                            Map.of(
                                    "path", Schemas.string("repository relative path", 1, 300),
                                    "search", Schemas.string("text to replace", 1, 20_000),
                                    "replace", Schemas.string("replacement text", 0, 20_000)),
                            List.of("path", "search", "replace")));

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        Workspace.EditRecord record =
                context.workspace()
                        .edit(
                                arguments.path("path").asString(),
                                arguments.path("search").asString(),
                                arguments.path("replace").asString());
        return ToolResult.ok(
                "edit "
                        + record.sequence()
                        + " applied to "
                        + record.path()
                        + " at line "
                        + record.line()
                        + ", "
                        + context.workspace().changedLines()
                        + " lines changed in total");
    }
}
