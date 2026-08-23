package dev.rook.agent.tools;

import dev.rook.agent.safety.PromptSanitizer;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.codeintel.java.CodeIndex;
import dev.rook.core.change.MigrationNote;
import java.util.List;

public record ToolContext(
        Workspace workspace,
        CodeIndex index,
        ImpactReport report,
        List<MigrationNote> notes,
        PromptSanitizer sanitizer) {

    public ToolContext {
        notes = List.copyOf(notes);
    }
}
