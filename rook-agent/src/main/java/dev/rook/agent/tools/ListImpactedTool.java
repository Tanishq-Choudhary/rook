package dev.rook.agent.tools;

import dev.rook.agent.model.ToolDefinition;
import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class ListImpactedTool implements Tool {

    private static final int MAX_LOCATIONS_PER_IMPACT = 25;

    private final ToolDefinition definition =
            new ToolDefinition(
                    "list_impacted",
                    "List the places in the repository that the upstream change affects, grouped by"
                            + " change, with the confidence of each match.",
                    Schemas.object(Map.of(), List.of()));

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        StringBuilder out = new StringBuilder();
        for (Impact impact : context.report().impacts()) {
            out.append(impact.severity())
                    .append(' ')
                    .append(impact.kind())
                    .append(' ')
                    .append(impact.subject())
                    .append(": ")
                    .append(impact.summary())
                    .append('\n');
            int shown = 0;
            for (ImpactLocation location : impact.locations()) {
                if (shown++ == MAX_LOCATIONS_PER_IMPACT) {
                    out.append("  ... ")
                            .append(impact.locations().size() - MAX_LOCATIONS_PER_IMPACT)
                            .append(" more\n");
                    break;
                }
                out.append("  ")
                        .append(location.file())
                        .append(':')
                        .append(location.line())
                        .append(' ')
                        .append(location.confidence())
                        .append(location.test() ? " test " : " main ")
                        .append(location.referenceKind())
                        .append(" in ")
                        .append(location.enclosing())
                        .append('\n');
            }
        }
        if (out.isEmpty()) {
            return ToolResult.ok("the analysis found no affected locations");
        }
        return ToolResult.ok(context.sanitizer().prepare("impact-report", out.toString()));
    }
}
