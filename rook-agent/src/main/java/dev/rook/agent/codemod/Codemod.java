package dev.rook.agent.codemod;

import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.List;

public interface Codemod {

    String name();

    CodemodPlan plan(Workspace workspace, List<ImpactLocation> locations);
}
