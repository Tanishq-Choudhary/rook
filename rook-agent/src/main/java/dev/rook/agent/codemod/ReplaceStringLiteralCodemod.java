package dev.rook.agent.codemod;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ReplaceStringLiteralCodemod implements Codemod {

    private static final Set<String> KINDS = Set.of("STRING_LITERAL", "ANNOTATION");

    private final String oldValue;
    private final String newValue;

    public ReplaceStringLiteralCodemod(String oldValue, String newValue) {
        if (oldValue.isEmpty() || oldValue.equals(newValue)) {
            throw new IllegalArgumentException("invalid literal replacement");
        }
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    @Override
    public String name() {
        return "replace-literal \"" + oldValue + "\" -> \"" + newValue + "\"";
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
                                List<Range> targets = find(loaded.get(), location);
                                if (targets.isEmpty()) {
                                    skipped.add(
                                            path
                                                    + ":"
                                                    + location.line()
                                                    + ": no literal \""
                                                    + oldValue
                                                    + "\" at that position");
                                    continue;
                                }
                                boolean changed = false;
                                for (Range target : targets) {
                                    changed |= edits.replace(target, quote(newValue));
                                }
                                if (changed) {
                                    handled.add(location);
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

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private List<Range> find(SourceFiles.Loaded file, ImpactLocation location) {
        Position at = new Position(location.line(), location.column());
        List<Range> exact = new ArrayList<>();
        List<Range> onLine = new ArrayList<>();
        for (StringLiteralExpr literal : file.java().unit().findAll(StringLiteralExpr.class)) {
            if (!literal.getValue().equals(oldValue) || literal.getRange().isEmpty()) {
                continue;
            }
            Range range = literal.getRange().get();
            if (location.referenceKind().equals("ANNOTATION")) {
                Node parent = literal.getParentNode().orElse(null);
                while (parent != null && !(parent instanceof AnnotationExpr)) {
                    parent = parent.getParentNode().orElse(null);
                }
                if (parent != null
                        && parent.getRange().isPresent()
                        && parent.getRange().get().begin.equals(at)) {
                    exact.add(range);
                }
                continue;
            }
            if (range.begin.equals(at)) {
                exact.add(range);
            } else if (range.begin.line == location.line()) {
                onLine.add(range);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        return onLine.size() == 1 ? onLine : List.of();
    }
}
