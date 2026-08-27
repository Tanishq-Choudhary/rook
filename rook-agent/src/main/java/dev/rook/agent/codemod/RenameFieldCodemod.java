package dev.rook.agent.codemod;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RenameFieldCodemod implements Codemod {

    private static final Set<String> KINDS = Set.of("FIELD_ACCESS", "SWITCH_LABEL");

    private final String oldName;
    private final String newName;

    public RenameFieldCodemod(String oldName, String newName) {
        if (oldName.equals(newName) || !RenameMethodCodemod.isIdentifier(newName)) {
            throw new IllegalArgumentException("invalid rename " + oldName + " -> " + newName);
        }
        this.oldName = oldName;
        this.newName = newName;
    }

    @Override
    public String name() {
        return "rename-field " + oldName + " -> " + newName;
    }

    @Override
    public CodemodPlan plan(Workspace workspace, List<ImpactLocation> locations) {
        List<CodemodPlan.FileRewrite> rewrites = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ImpactLocation> handled = new ArrayList<>();
        SourceFiles.byFile(locations, KINDS)
                .forEach(
                        (path, fileLocations) -> {
                            Optional<SourceFiles.Loaded> loaded =
                                    SourceFiles.load(
                                            workspace, path, fileLocations, skipped, false);
                            if (loaded.isEmpty()) {
                                return;
                            }
                            SourceEdits edits = new SourceEdits(loaded.get().java().content());
                            for (ImpactLocation location : fileLocations) {
                                Range target = find(loaded.get(), location);
                                if (target != null && edits.replace(target, newName)) {
                                    handled.add(location);
                                } else if (target == null) {
                                    skipped.add(
                                            path
                                                    + ":"
                                                    + location.line()
                                                    + ": no use of "
                                                    + oldName
                                                    + " at that position");
                                }
                            }
                            if (edits.count() > 0) {
                                rewrites.add(
                                        new CodemodPlan.FileRewrite(
                                                path, edits.apply(), edits.count()));
                            }
                        });
        return new CodemodPlan(name(), rewrites, skipped, SourceFiles.weakest(handled), handled);
    }

    private Range find(SourceFiles.Loaded file, ImpactLocation location) {
        Position at = new Position(location.line(), location.column());
        List<Range> onLine = new ArrayList<>();
        for (FieldAccessExpr access : file.java().unit().findAll(FieldAccessExpr.class)) {
            if (access.getNameAsString().equals(oldName)
                    && access.getRange().isPresent()
                    && access.getName().getRange().isPresent()) {
                if (access.getRange().get().begin.equals(at)) {
                    return access.getName().getRange().get();
                }
                if (access.getRange().get().begin.line == location.line()) {
                    onLine.add(access.getName().getRange().get());
                }
            }
        }
        for (NameExpr name : file.java().unit().findAll(NameExpr.class)) {
            if (name.getNameAsString().equals(oldName) && name.getRange().isPresent()) {
                if (name.getRange().get().begin.equals(at)) {
                    return name.getRange().get();
                }
                if (name.getRange().get().begin.line == location.line()) {
                    onLine.add(name.getRange().get());
                }
            }
        }
        return onLine.size() == 1 ? onLine.get(0) : null;
    }
}
