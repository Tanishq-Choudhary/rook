package dev.rook.agent.codemod;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RenameMethodCodemod implements Codemod {

    private static final Set<String> KINDS = Set.of("METHOD_CALL", "METHOD_REFERENCE");

    private final String oldName;
    private final String newName;

    public RenameMethodCodemod(String oldName, String newName) {
        if (oldName.equals(newName) || !isIdentifier(newName)) {
            throw new IllegalArgumentException(
                    "invalid method rename " + oldName + " -> " + newName);
        }
        this.oldName = oldName;
        this.newName = newName;
    }

    static boolean isIdentifier(String name) {
        return name.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }

    @Override
    public String name() {
        return "rename-method " + oldName + " -> " + newName;
    }

    @Override
    public CodemodPlan plan(Workspace workspace, List<ImpactLocation> locations) {
        List<CodemodPlan.FileRewrite> rewrites = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ImpactLocation> used = new ArrayList<>();
        SourceFiles.byFile(locations, KINDS)
                .forEach(
                        (path, fileLocations) -> {
                            Optional<SourceFiles.Loaded> loaded =
                                    SourceFiles.load(workspace, path, fileLocations, skipped);
                            if (loaded.isEmpty()) {
                                return;
                            }
                            int edits = rename(loaded.get(), skipped, used);
                            if (edits > 0) {
                                rewrites.add(
                                        new CodemodPlan.FileRewrite(
                                                path, loaded.get().java().print(), edits));
                            }
                        });
        return new CodemodPlan(name(), rewrites, skipped, SourceFiles.weakest(used), used);
    }

    private int rename(SourceFiles.Loaded file, List<String> skipped, List<ImpactLocation> used) {
        List<MethodCallExpr> calls = file.java().unit().findAll(MethodCallExpr.class);
        List<MethodReferenceExpr> references =
                file.java().unit().findAll(MethodReferenceExpr.class);
        Set<Node> renamed = new HashSet<>();
        int edits = 0;
        for (ImpactLocation location : file.locations()) {
            Node target = find(calls, references, location);
            if (target == null) {
                if (!alreadyRenamed(calls, references, location)) {
                    skipped.add(
                            location.file()
                                    + ":"
                                    + location.line()
                                    + ": no call of "
                                    + oldName
                                    + " at that position");
                }
                continue;
            }
            if (!renamed.add(target)) {
                continue;
            }
            if (target instanceof MethodCallExpr call) {
                call.setName(newName);
            } else if (target instanceof MethodReferenceExpr reference) {
                reference.setIdentifier(newName);
            }
            used.add(location);
            edits++;
        }
        return edits;
    }

    private Node find(
            List<MethodCallExpr> calls,
            List<MethodReferenceExpr> references,
            ImpactLocation location) {
        Position at = new Position(location.line(), location.column());
        List<Node> onLine = new ArrayList<>();
        for (MethodCallExpr call : calls) {
            if (call.getNameAsString().equals(oldName) && call.getRange().isPresent()) {
                if (call.getRange().get().begin.equals(at)) {
                    return call;
                }
                if (call.getRange().get().begin.line == location.line()) {
                    onLine.add(call);
                }
            }
        }
        for (MethodReferenceExpr reference : references) {
            if (reference.getIdentifier().equals(oldName) && reference.getRange().isPresent()) {
                if (reference.getRange().get().begin.equals(at)) {
                    return reference;
                }
                if (reference.getRange().get().begin.line == location.line()) {
                    onLine.add(reference);
                }
            }
        }
        return onLine.size() == 1 ? onLine.get(0) : null;
    }

    private boolean alreadyRenamed(
            List<MethodCallExpr> calls,
            List<MethodReferenceExpr> references,
            ImpactLocation location) {
        for (MethodCallExpr call : calls) {
            if (call.getNameAsString().equals(newName)
                    && call.getRange().isPresent()
                    && call.getRange().get().begin.line == location.line()) {
                return true;
            }
        }
        for (MethodReferenceExpr reference : references) {
            if (reference.getIdentifier().equals(newName)
                    && reference.getRange().isPresent()
                    && reference.getRange().get().begin.line == location.line()) {
                return true;
            }
        }
        return false;
    }
}
