package dev.rook.agent.tools;

import dev.rook.agent.model.ToolDefinition;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class ReadFileTool implements Tool {

    private static final int DEFAULT_WINDOW = 200;

    private final ToolDefinition definition =
            new ToolDefinition(
                    "read_file",
                    "Read a text file from the repository. Give start and end line numbers (1 based,"
                            + " inclusive) to read a window, otherwise the first "
                            + DEFAULT_WINDOW
                            + " lines are returned. Lines are numbered.",
                    Schemas.object(
                            Map.of(
                                    "path", Schemas.string("repository relative path", 1, 300),
                                    "start", Schemas.integer("first line", 1, 1_000_000),
                                    "end", Schemas.integer("last line", 1, 1_000_000)),
                            List.of("path")));

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String path = arguments.path("path").asString();
        int start = arguments.path("start").asInt(1);
        int end = arguments.has("end") ? arguments.path("end").asInt() : start + DEFAULT_WINDOW - 1;
        String numbered = context.workspace().readLines(path, start, end);
        if (numbered.isEmpty()) {
            return ToolResult.ok("no lines in that range of " + path);
        }
        return ToolResult.ok(context.sanitizer().prepare(path, numbered));
    }
}
