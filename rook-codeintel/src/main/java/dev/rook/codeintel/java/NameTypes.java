package dev.rook.codeintel.java;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.PatternExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Optional;

final class NameTypes {

    private NameTypes() {}

    static String lookup(Node origin, String name) {
        Position at = origin.getBegin().orElse(null);
        Node current = origin;
        while (current != null) {
            String found = declaredIn(current, name, at);
            if (found != null) {
                return found;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    static String erase(String type) {
        if (type == null) {
            return null;
        }
        int generic = type.indexOf('<');
        String raw = generic < 0 ? type : type.substring(0, generic);
        return raw.endsWith("[]") ? raw.substring(0, raw.length() - 2) : raw;
    }

    private static String declaredIn(Node node, String name, Position origin) {
        if (node instanceof BlockStmt block) {
            for (Statement statement : block.getStatements()) {
                if (origin != null
                        && statement.getBegin().isPresent()
                        && !statement.getBegin().get().isBefore(origin)) {
                    break;
                }
                String type = fromStatement(statement, name);
                if (type != null) {
                    return type;
                }
            }
        }
        if (node instanceof MethodDeclaration method) {
            return fromParameters(method.getParameters(), name);
        }
        if (node instanceof ConstructorDeclaration constructor) {
            return fromParameters(constructor.getParameters(), name);
        }
        if (node instanceof LambdaExpr lambda) {
            return fromParameters(lambda.getParameters(), name);
        }
        if (node instanceof CatchClause clause
                && clause.getParameter().getNameAsString().equals(name)) {
            return erase(clause.getParameter().getType().asString());
        }
        if (node instanceof ForStmt loop) {
            for (var init : loop.getInitialization()) {
                if (init instanceof VariableDeclarationExpr declaration) {
                    String type = fromDeclaration(declaration, name);
                    if (type != null) {
                        return type;
                    }
                }
            }
        }
        if (node instanceof ForEachStmt each) {
            String type = fromDeclaration(each.getVariable(), name);
            if (type != null) {
                return type;
            }
        }
        if (node instanceof TryStmt tryStatement) {
            for (var resource : tryStatement.getResources()) {
                if (resource instanceof VariableDeclarationExpr declaration) {
                    String type = fromDeclaration(declaration, name);
                    if (type != null) {
                        return type;
                    }
                }
            }
        }
        if (node instanceof TypePatternExpr pattern && pattern.getNameAsString().equals(name)) {
            return erase(pattern.getType().asString());
        }
        if (node instanceof PatternExpr) {
            return null;
        }
        if (node instanceof TypeDeclaration<?> declaration) {
            for (FieldDeclaration field : declaration.getFields()) {
                for (VariableDeclarator variable : field.getVariables()) {
                    if (variable.getNameAsString().equals(name)) {
                        return typeOf(variable);
                    }
                }
            }
            if (declaration instanceof RecordDeclaration record) {
                for (Parameter component : record.getParameters()) {
                    if (component.getNameAsString().equals(name)) {
                        return erase(component.getType().asString());
                    }
                }
            }
        }
        return null;
    }

    private static String fromStatement(Statement statement, String name) {
        if (statement.isExpressionStmt()
                && statement.asExpressionStmt().getExpression()
                        instanceof VariableDeclarationExpr declaration) {
            return fromDeclaration(declaration, name);
        }
        return null;
    }

    private static String fromDeclaration(VariableDeclarationExpr declaration, String name) {
        for (VariableDeclarator variable : declaration.getVariables()) {
            if (variable.getNameAsString().equals(name)) {
                return typeOf(variable);
            }
        }
        return null;
    }

    private static String fromParameters(List<Parameter> parameters, String name) {
        for (Parameter parameter : parameters) {
            if (parameter.getNameAsString().equals(name)) {
                Type type = parameter.getType();
                return type.isUnknownType() ? null : erase(type.asString());
            }
        }
        return null;
    }

    private static String typeOf(VariableDeclarator variable) {
        Type type = variable.getType();
        if (type.isVarType()) {
            Optional<com.github.javaparser.ast.expr.Expression> initializer =
                    variable.getInitializer();
            if (initializer.isPresent()
                    && initializer.get() instanceof ObjectCreationExpr creation) {
                return erase(creation.getType().asString());
            }
            return null;
        }
        return erase(type.asString());
    }
}
