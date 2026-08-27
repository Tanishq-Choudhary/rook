package dev.rook.agent.codemod;

import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspaceViolation;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SourceFiles {

    record Loaded(String path, ParsedJava java, List<ImpactLocation> locations) {}

    private SourceFiles() {}

    static Map<String, List<ImpactLocation>> byFile(
            List<ImpactLocation> locations, Set<String> kinds) {
        Map<String, List<ImpactLocation>> grouped = new LinkedHashMap<>();
        for (ImpactLocation location : locations) {
            if (kinds.isEmpty() || kinds.contains(location.referenceKind())) {
                grouped.computeIfAbsent(location.file(), k -> new ArrayList<>()).add(location);
            }
        }
        return grouped;
    }

    static Optional<Loaded> load(
            Workspace workspace,
            String path,
            List<ImpactLocation> locations,
            List<String> skipped) {
        return load(workspace, path, locations, skipped, true);
    }

    static Optional<Loaded> load(
            Workspace workspace,
            String path,
            List<ImpactLocation> locations,
            List<String> skipped,
            boolean lexical) {
        String content;
        try {
            content = workspace.read(path);
        } catch (WorkspaceViolation e) {
            skipped.add(path + ": " + e.getMessage());
            return Optional.empty();
        }
        Optional<ParsedJava> parsed = ParsedJava.parse(content, lexical);
        if (parsed.isEmpty()) {
            skipped.add(path + ": the file does not parse");
            return Optional.empty();
        }
        return Optional.of(new Loaded(path, parsed.get(), locations));
    }

    static Confidence weakest(List<ImpactLocation> used) {
        Confidence weakest = Confidence.RESOLVED;
        for (ImpactLocation location : used) {
            if (location.confidence().compareTo(weakest) < 0) {
                weakest = location.confidence();
            }
        }
        return weakest;
    }
}
