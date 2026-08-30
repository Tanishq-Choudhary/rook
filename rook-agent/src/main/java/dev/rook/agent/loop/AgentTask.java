package dev.rook.agent.loop;

import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.codeintel.java.CodeIndex;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.MigrationNote;
import java.util.List;

public record AgentTask(
        String repository,
        List<ChangeEvent> events,
        ImpactReport report,
        List<MigrationNote> notes,
        CodeIndex index) {

    public AgentTask {
        events = List.copyOf(events);
        notes = List.copyOf(notes);
    }
}
