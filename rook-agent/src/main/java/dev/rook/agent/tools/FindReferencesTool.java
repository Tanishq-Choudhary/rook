package dev.rook.agent.tools;

import dev.rook.agent.model.ToolDefinition;
import dev.rook.codeintel.java.Model;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public final class FindReferencesTool implements Tool {

    private static final int MAX_RESULTS = 60;

    private final ToolDefinition definition =
            new ToolDefinition(
                    "find_references",
                    "Find usages (calls, type uses, field accesses, string literals) of a simple"
                            + " name in the repository.",
                    Schemas.object(
                            Map.of(
                                    "name",
                                    Schemas.string(
                                            "simple name of a method, type or field", 1, 120)),
                            List.of("name")));

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String name = arguments.path("name").asString();
        List<Model.Usage> usages =
                context.index()
                        .usages()
                        .filter(usage -> usage.simpleName().equals(name))
                        .limit(MAX_RESULTS + 1)
                        .collect(Collectors.toList());
        if (usages.isEmpty()) {
            return ToolResult.ok("no references to " + name + " were found");
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Model.Usage usage : usages) {
            if (shown++ == MAX_RESULTS) {
                out.append("[more references exist]\n");
                break;
            }
            out.append(usage.location().file())
                    .append(':')
                    .append(usage.location().line())
                    .append(' ')
                    .append(usage.kind())
                    .append(" in ")
                    .append(usage.enclosing())
                    .append(": ")
                    .append(usage.snippet().strip())
                    .append('\n');
        }
        return ToolResult.ok(context.sanitizer().prepare("references-to-" + name, out.toString()));
    }
}
