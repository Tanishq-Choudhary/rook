package dev.rook.agent.loop;

import dev.rook.agent.model.ToolDefinition;
import dev.rook.agent.tools.Schemas;
import dev.rook.agent.tools.Tool;
import dev.rook.agent.tools.ToolContext;
import dev.rook.agent.tools.ToolResult;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class RunTestsTool implements Tool {

    public static final String NAME = "run_tests";

    private final SandboxPort sandbox;
    private final AgentListener listener;
    private final ToolDefinition definition =
            new ToolDefinition(
                    NAME,
                    "Compile the repository and run its tests in an isolated sandbox with your"
                            + " current edits applied. Use scope \"all\" or the name of a test class.",
                    Schemas.object(
                            Map.of("scope", Schemas.string("all or a test class name", 1, 200)),
                            List.of()));

    public RunTestsTool(SandboxPort sandbox, AgentListener listener) {
        this.sandbox = sandbox;
        this.listener = listener;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String scope = arguments.path("scope").asString("all");
        TestRun run = sandbox.run(context.workspace().root(), scope);
        listener.testsRun(run);
        String text = context.sanitizer().prepare("test-output", run.describe());
        return run.passed() ? ToolResult.ok(text) : ToolResult.failure(text);
    }
}
