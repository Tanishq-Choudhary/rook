package dev.rook.agent.tools;

import dev.rook.agent.model.ToolDefinition;
import dev.rook.codeintel.java.Model;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class SearchSymbolTool implements Tool {

    private static final int MAX_RESULTS = 50;

    private final ToolDefinition definition =
            new ToolDefinition(
                    "search_symbol",
                    "Find types, methods and fields in the repository whose name contains the query"
                            + " (case insensitive). Returns the declaring file and line.",
                    Schemas.object(
                            Map.of("query", Schemas.string("part of a symbol name", 1, 120)),
                            List.of("query")));

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(JsonNode arguments, ToolContext context) {
        String query = arguments.path("query").asString().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int found = 0;
        for (Model.TypeInfo type : context.index().types().values()) {
            if (type.simpleName().toLowerCase(Locale.ROOT).contains(query)) {
                out.append("TYPE ")
                        .append(type.qualifiedName())
                        .append(" (")
                        .append(type.kind())
                        .append(") ")
                        .append(type.file())
                        .append(':')
                        .append(type.line())
                        .append('\n');
                found++;
            }
            for (Model.MethodInfo method : type.methods()) {
                if (found < MAX_RESULTS && method.name().toLowerCase(Locale.ROOT).contains(query)) {
                    out.append("METHOD ")
                            .append(type.qualifiedName())
                            .append('#')
                            .append(method.name())
                            .append('(')
                            .append(String.join(", ", method.parameterTypes()))
                            .append(") ")
                            .append(type.file())
                            .append(':')
                            .append(method.line())
                            .append('\n');
                    found++;
                }
            }
            for (Model.FieldInfo field : type.fields()) {
                if (found < MAX_RESULTS && field.name().toLowerCase(Locale.ROOT).contains(query)) {
                    out.append("FIELD ")
                            .append(type.qualifiedName())
                            .append('.')
                            .append(field.name())
                            .append(' ')
                            .append(type.file())
                            .append(':')
                            .append(field.line())
                            .append('\n');
                    found++;
                }
            }
            if (found >= MAX_RESULTS) {
                out.append("[more results exist, narrow the query]\n");
                break;
            }
        }
        if (found == 0) {
            return ToolResult.ok("no symbol name contains " + arguments.path("query").asString());
        }
        return ToolResult.ok(out.toString());
    }
}
