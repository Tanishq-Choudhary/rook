package dev.rook.agent.codemod;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class DropArgumentCodemod implements Codemod {

    private static final Set<String> KINDS = Set.of("METHOD_CALL");

    private final String method;
    private final int index;

    public DropArgumentCodemod(String method, int index) {
        if (index < 0 || !RenameMethodCodemod.isIdentifier(method)) {
            throw new IllegalArgumentException("invalid argument removal");
        }
        this.method = method;
        this.index = index;
    }

    @Override
    public String name() {
        return "drop-argument " + method + "#" + index;
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
                                MethodCallExpr call = find(loaded.get(), location);
                                if (call == null) {
                                    skipped.add(
                                            path
                                                    + ":"
                                                    + location.line()
                                                    + ": no call of "
                                                    + method
                                                    + " at that position");
                                    continue;
                                }
                                if (call.getArguments().size() <= index) {
                                    continue;
                                }
                                if (!isPure(call.getArgument(index))) {
                                    skipped.add(
                                            path
                                                    + ":"
                                                    + location.line()
                                                    + ": the argument may have side effects");
                                    continue;
                                }
                                Range removal = removal(call);
                                if (removal != null && edits.replace(removal, "")) {
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

    private Range removal(MethodCallExpr call) {
        List<Expression> arguments = call.getArguments();
        Expression target = arguments.get(index);
        if (target.getRange().isEmpty()) {
            return null;
        }
        Range own = target.getRange().get();
        if (arguments.size() == 1) {
            return own;
        }
        if (index + 1 < arguments.size() && arguments.get(index + 1).getRange().isPresent()) {
            return new Range(own.begin, previous(arguments.get(index + 1).getRange().get().begin));
        }
        if (arguments.get(index - 1).getRange().isPresent()) {
            return new Range(next(arguments.get(index - 1).getRange().get().end), own.end);
        }
        return null;
    }

    private static Position previous(Position position) {
        return new Position(position.line, position.column - 1);
    }

    private static Position next(Position position) {
        return new Position(position.line, position.column + 1);
    }

    private static boolean isPure(Expression expression) {
        if (expression instanceof LiteralExpr
                || expression instanceof NameExpr
                || expression instanceof ThisExpr) {
            return true;
        }
        if (expression instanceof FieldAccessExpr access) {
            return isPure(access.getScope());
        }
        if (expression instanceof UnaryExpr unary) {
            return unary.getOperator() == UnaryExpr.Operator.MINUS && isPure(unary.getExpression());
        }
        return false;
    }

    private MethodCallExpr find(SourceFiles.Loaded file, ImpactLocation location) {
        Position at = new Position(location.line(), location.column());
        List<MethodCallExpr> onLine = new ArrayList<>();
        for (MethodCallExpr call : file.java().unit().findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals(method) && call.getRange().isPresent()) {
                if (call.getRange().get().begin.equals(at)) {
                    return call;
                }
                if (call.getRange().get().begin.line == location.line()) {
                    onLine.add(call);
                }
            }
        }
        return onLine.size() == 1 ? onLine.get(0) : null;
    }
}
