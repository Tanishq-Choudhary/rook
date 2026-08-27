package dev.rook.agent.codemod;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RenameTypeCodemod implements Codemod {

    private final String oldQualified;
    private final String newQualified;
    private final String oldSimple;
    private final String newSimple;
    private final String oldPackage;
    private final String newPackage;

    public RenameTypeCodemod(String oldQualified, String newQualified) {
        if (oldQualified.equals(newQualified)) {
            throw new IllegalArgumentException("the type keeps the same name");
        }
        this.oldQualified = oldQualified;
        this.newQualified = newQualified;
        this.oldSimple = simple(oldQualified);
        this.newSimple = simple(newQualified);
        this.oldPackage = packageOf(oldQualified);
        this.newPackage = packageOf(newQualified);
        if (!RenameMethodCodemod.isIdentifier(oldSimple)
                || !RenameMethodCodemod.isIdentifier(newSimple)) {
            throw new IllegalArgumentException("invalid type names");
        }
    }

    private static String simple(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static String packageOf(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    @Override
    public String name() {
        return "rename-type " + oldQualified + " -> " + newQualified;
    }

    @Override
    public CodemodPlan plan(Workspace workspace, List<ImpactLocation> locations) {
        List<CodemodPlan.FileRewrite> rewrites = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ImpactLocation> handled = new ArrayList<>();
        SourceFiles.byFile(locations, Set.of())
                .forEach(
                        (path, fileLocations) -> {
                            Optional<SourceFiles.Loaded> loaded =
                                    SourceFiles.load(
                                            workspace, path, fileLocations, skipped, false);
                            if (loaded.isEmpty()) {
                                return;
                            }
                            SourceEdits edits = new SourceEdits(loaded.get().java().content());
                            plan(loaded.get().java().unit(), path, edits, skipped);
                            if (edits.count() > 0) {
                                rewrites.add(
                                        new CodemodPlan.FileRewrite(
                                                path, edits.apply(), edits.count()));
                                handled.addAll(fileLocations);
                            }
                        });
        return new CodemodPlan(name(), rewrites, skipped, SourceFiles.weakest(handled), handled);
    }

    private void plan(CompilationUnit unit, String path, SourceEdits edits, List<String> skipped) {
        String filePackage =
                unit.getPackageDeclaration().map(PackageDeclaration::getNameAsString).orElse("");
        boolean importsOld = false;
        boolean wildcardOld = false;
        for (ImportDeclaration declaration : unit.getImports()) {
            if (!declaration.isStatic() && !declaration.isAsterisk()) {
                if (declaration.getNameAsString().equals(oldQualified)) {
                    importsOld = true;
                } else if (simple(declaration.getNameAsString()).equals(oldSimple)
                        && !oldSimple.equals(newSimple)) {
                    skipped.add(path + ": another type named " + oldSimple + " is imported");
                    return;
                }
            }
            if (!declaration.isStatic()
                    && declaration.isAsterisk()
                    && declaration.getNameAsString().equals(oldPackage)) {
                wildcardOld = true;
            }
        }
        boolean simpleNameBinds = importsOld || wildcardOld || filePackage.equals(oldPackage);
        boolean usedBefore = usesSimpleName(unit, oldSimple);
        renameImports(unit, edits);
        if (simpleNameBinds && !shadowed(unit)) {
            renameSimpleUses(unit, edits);
        }
        renameQualifiedUses(unit, edits);
        boolean needsImport =
                !importsOld
                        && (wildcardOld || filePackage.equals(oldPackage))
                        && !newPackage.equals(filePackage)
                        && usedBefore
                        && unit.getImports().stream()
                                .noneMatch(i -> i.getNameAsString().equals(newQualified));
        if (needsImport) {
            addImport(unit, edits);
        }
    }

    private boolean shadowed(CompilationUnit unit) {
        boolean variable =
                unit.findAll(VariableDeclarator.class).stream()
                        .anyMatch(v -> v.getNameAsString().equals(oldSimple));
        boolean parameter =
                unit.findAll(Parameter.class).stream()
                        .anyMatch(p -> p.getNameAsString().equals(oldSimple));
        return variable || parameter;
    }

    private void renameImports(CompilationUnit unit, SourceEdits edits) {
        for (ImportDeclaration declaration : unit.getImports()) {
            if (declaration.isAsterisk() && !declaration.isStatic()) {
                continue;
            }
            Name chain = declaration.getName();
            while (chain != null) {
                if (chain.asString().equals(oldQualified) && chain.getRange().isPresent()) {
                    edits.replace(chain.getRange().get(), newQualified);
                    break;
                }
                chain = chain.getQualifier().orElse(null);
            }
        }
    }

    private void renameSimpleUses(CompilationUnit unit, SourceEdits edits) {
        if (oldSimple.equals(newSimple)) {
            return;
        }
        for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
            if (type.getScope().isEmpty()
                    && type.getNameAsString().equals(oldSimple)
                    && type.getName().getRange().isPresent()) {
                edits.replace(type.getName().getRange().get(), newSimple);
            }
        }
        for (NameExpr expression : unit.findAll(NameExpr.class)) {
            if (expression.getNameAsString().equals(oldSimple)
                    && expression.getName().getRange().isPresent()) {
                edits.replace(expression.getName().getRange().get(), newSimple);
            }
        }
        for (AnnotationExpr annotation : unit.findAll(AnnotationExpr.class)) {
            Name root = annotation.getName();
            while (root.getQualifier().isPresent()) {
                root = root.getQualifier().get();
            }
            if (root.getIdentifier().equals(oldSimple) && root.getRange().isPresent()) {
                edits.replace(root.getRange().get(), newSimple);
            }
        }
    }

    private void renameQualifiedUses(CompilationUnit unit, SourceEdits edits) {
        for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
            if (type.getScope().isPresent()
                    && type.getNameWithScope().equals(oldQualified)
                    && type.getScope().get().getRange().isPresent()
                    && type.getName().getRange().isPresent()) {
                Range whole =
                        new Range(
                                type.getScope().get().getRange().get().begin,
                                type.getName().getRange().get().end);
                edits.replace(whole, newQualified);
            }
        }
    }

    private void addImport(CompilationUnit unit, SourceEdits edits) {
        String statement = "import " + newQualified + ";";
        if (!unit.getImports().isEmpty()) {
            ImportDeclaration last = unit.getImports().get(unit.getImports().size() - 1);
            last.getRange().ifPresent(range -> edits.insertAfter(range, "\n" + statement));
            return;
        }
        unit.getPackageDeclaration()
                .flatMap(PackageDeclaration::getRange)
                .ifPresent(range -> edits.insertAfter(range, "\n\n" + statement));
    }

    private static boolean usesSimpleName(CompilationUnit unit, String name) {
        return unit.findAll(ClassOrInterfaceType.class).stream()
                        .anyMatch(t -> t.getScope().isEmpty() && t.getNameAsString().equals(name))
                || unit.findAll(NameExpr.class).stream()
                        .anyMatch(n -> n.getNameAsString().equals(name));
    }
}
