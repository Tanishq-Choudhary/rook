package dev.rook.codeintel.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.rook.codeintel.java.Model.Usage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SymbolResolver {

    private static final Logger log = LoggerFactory.getLogger(SymbolResolver.class);

    public record Resolved(String owner, String member, String signature) {}

    private final CodeIndex index;
    private final JavaSymbolSolver solver;

    private SymbolResolver(CodeIndex index, JavaSymbolSolver solver) {
        this.index = index;
        this.solver = solver;
    }

    public static SymbolResolver create(
            CodeIndex index, List<String> sourceRoots, List<Path> jars) {
        CombinedTypeSolver combined = new CombinedTypeSolver();
        combined.add(new ReflectionTypeSolver(true));
        for (String sourceRoot : sourceRoots) {
            Path directory = index.root().resolve(sourceRoot);
            if (Files.isDirectory(directory)) {
                combined.add(new JavaParserTypeSolver(directory));
            }
        }
        for (Path jar : jars) {
            if (Files.isRegularFile(jar)) {
                try {
                    try (var in = Files.newInputStream(jar)) {
                        combined.add(new JarTypeSolver(in));
                    }
                } catch (IOException e) {
                    log.warn(
                            "cannot read {} for symbol resolution: {}",
                            jar.getFileName(),
                            e.getMessage());
                }
            }
        }
        return new SymbolResolver(index, new JavaSymbolSolver((TypeSolver) combined));
    }

    public Optional<Resolved> resolve(Usage usage) {
        Optional<CompilationUnit> unit = index.unit(usage.location().file());
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        CompilationUnit compilationUnit = unit.get();
        solver.inject(compilationUnit);
        try {
            return switch (usage.kind()) {
                case METHOD_CALL -> resolveCall(compilationUnit, usage);
                case CONSTRUCTOR_CALL -> resolveConstructor(compilationUnit, usage);
                case FIELD_ACCESS -> resolveField(compilationUnit, usage);
                case METHOD_REFERENCE -> resolveReference(compilationUnit, usage);
                case TYPE_USE -> resolveType(compilationUnit, usage);
                case SWITCH_LABEL -> resolveLabel(compilationUnit, usage);
                default -> Optional.empty();
            };
        } catch (RuntimeException | StackOverflowError e) {
            log.debug("cannot resolve {} at {}: {}", usage.name(), usage.location(), e.toString());
            return Optional.empty();
        }
    }

    private Optional<Resolved> resolveCall(CompilationUnit unit, Usage usage) {
        for (MethodCallExpr call :
                unit.findAll(
                        MethodCallExpr.class,
                        n -> at(n, usage) && n.getNameAsString().equals(usage.name()))) {
            var method = call.resolve();
            return Optional.of(
                    new Resolved(
                            method.declaringType().getQualifiedName(),
                            method.getName(),
                            method.getQualifiedSignature()));
        }
        return Optional.empty();
    }

    private Optional<Resolved> resolveConstructor(CompilationUnit unit, Usage usage) {
        for (ObjectCreationExpr creation :
                unit.findAll(ObjectCreationExpr.class, n -> at(n, usage))) {
            var constructor = creation.resolve();
            return Optional.of(
                    new Resolved(
                            constructor.declaringType().getQualifiedName(),
                            "<init>",
                            constructor.getQualifiedSignature()));
        }
        return Optional.empty();
    }

    private Optional<Resolved> resolveField(CompilationUnit unit, Usage usage) {
        for (FieldAccessExpr access :
                unit.findAll(
                        FieldAccessExpr.class,
                        n -> at(n, usage) && n.getNameAsString().equals(usage.name()))) {
            var value = access.resolve();
            if (value.isEnumConstant()) {
                String owner =
                        value.asEnumConstant().getType().asReferenceType().getQualifiedName();
                return Optional.of(new Resolved(owner, usage.name(), owner + "#" + usage.name()));
            }
            if (value.isField()) {
                var field = value.asField();
                return Optional.of(
                        new Resolved(
                                field.declaringType().getQualifiedName(),
                                field.getName(),
                                field.declaringType().getQualifiedName() + "#" + field.getName()));
            }
        }
        return Optional.empty();
    }

    private Optional<Resolved> resolveReference(CompilationUnit unit, Usage usage) {
        for (MethodReferenceExpr reference :
                unit.findAll(MethodReferenceExpr.class, n -> at(n, usage))) {
            ResolvedMethodLikeDeclaration method = reference.resolve();
            return Optional.of(
                    new Resolved(
                            method.declaringType().getQualifiedName(),
                            method.getName(),
                            method.getQualifiedSignature()));
        }
        return Optional.empty();
    }

    private Optional<Resolved> resolveType(CompilationUnit unit, Usage usage) {
        for (ClassOrInterfaceType type :
                unit.findAll(ClassOrInterfaceType.class, n -> at(n, usage))) {
            var resolved = type.resolve();
            if (resolved.isReferenceType()) {
                ResolvedReferenceType reference = resolved.asReferenceType();
                return Optional.of(
                        new Resolved(
                                reference.getQualifiedName(), "", reference.getQualifiedName()));
            }
        }
        return Optional.empty();
    }

    private Optional<Resolved> resolveLabel(CompilationUnit unit, Usage usage) {
        for (NameExpr name : unit.findAll(NameExpr.class, n -> at(n, usage))) {
            SwitchEntry entry = name.findAncestor(SwitchEntry.class).orElse(null);
            if (entry == null) {
                continue;
            }
            Node parent = entry.getParentNode().orElse(null);
            if (parent instanceof SwitchStmt statement) {
                var type = statement.getSelector().calculateResolvedType();
                if (type.isReferenceType()) {
                    String owner = type.asReferenceType().getQualifiedName();
                    return Optional.of(
                            new Resolved(owner, usage.name(), owner + "#" + usage.name()));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean at(Node node, Usage usage) {
        return node.getBegin()
                .map(
                        p ->
                                p.line == usage.location().line()
                                        && p.column == usage.location().column())
                .orElse(false);
    }
}
