package dev.rook.codeintel.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import dev.rook.codeintel.RepoFiles;
import dev.rook.codeintel.java.Model.FieldInfo;
import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.ImportInfo;
import dev.rook.codeintel.java.Model.Location;
import dev.rook.codeintel.java.Model.MethodInfo;
import dev.rook.codeintel.java.Model.TypeInfo;
import dev.rook.codeintel.java.Model.Usage;
import dev.rook.codeintel.java.Model.UsageKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class JavaIndexer {

    private static final int MAX_STRING = 400;
    private static final int MAX_SNIPPET = 200;

    private static final ThreadLocal<JavaParser> PARSER =
            ThreadLocal.withInitial(
                    () -> {
                        ParserConfiguration configuration = new ParserConfiguration();
                        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
                        configuration.setAttributeComments(false);
                        configuration.setStoreTokens(true);
                        return new JavaParser(configuration);
                    });

    private JavaIndexer() {}

    public static CodeIndex index(Path root) {
        return index(root, List.of());
    }

    public static CodeIndex index(Path root, List<String> sourceRoots) {
        List<RepoFiles.RepoFile> candidates =
                RepoFiles.walk(root).stream()
                        .filter(f -> f.path().endsWith(".java"))
                        .filter(f -> inRoots(f.path(), sourceRoots))
                        .toList();
        ConcurrentMap<String, FileIndex> indexed = new ConcurrentHashMap<>();
        ConcurrentMap<String, CompilationUnit> units = new ConcurrentHashMap<>();
        ConcurrentMap<String, String> problems = new ConcurrentHashMap<>();
        candidates.parallelStream()
                .forEach(
                        file -> {
                            String source = RepoFiles.read(root, file.path(), 1024 * 1024);
                            ParseResult<CompilationUnit> result = PARSER.get().parse(source);
                            if (result.getResult().isEmpty()) {
                                problems.put(file.path(), summarize(result));
                                return;
                            }
                            if (!result.isSuccessful()) {
                                problems.put(file.path(), summarize(result));
                            }
                            CompilationUnit unit = result.getResult().get();
                            units.put(file.path(), unit);
                            indexed.put(file.path(), indexFile(file.path(), source, unit));
                        });
        Map<String, FileIndex> ordered = new LinkedHashMap<>();
        indexed.keySet().stream().sorted().forEach(path -> ordered.put(path, indexed.get(path)));
        List<String> sortedProblems =
                problems.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + ": " + e.getValue())
                        .collect(Collectors.toList());
        return new CodeIndex(root, ordered, units, sortedProblems);
    }

    private static boolean inRoots(String path, List<String> roots) {
        if (roots.isEmpty()) {
            return true;
        }
        return roots.stream().anyMatch(r -> path.startsWith(r.endsWith("/") ? r : r + "/"));
    }

    private static String summarize(ParseResult<CompilationUnit> result) {
        return result.getProblems().stream()
                .limit(3)
                .map(p -> p.getMessage().replace('\n', ' '))
                .collect(Collectors.joining("; "));
    }

    private static FileIndex indexFile(String path, String source, CompilationUnit unit) {
        String[] lines = source.split("\\R", -1);
        String packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<ImportInfo> imports = new ArrayList<>();
        unit.getImports()
                .forEach(
                        i ->
                                imports.add(
                                        new ImportInfo(
                                                i.getNameAsString(),
                                                i.isStatic(),
                                                i.isAsterisk(),
                                                line(i))));
        List<TypeInfo> types = new ArrayList<>();
        for (var declaration : unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class)) {
            types.add(typeOf(path, declaration));
        }
        List<Usage> usages = new ArrayList<>();
        collectUsages(path, unit, lines, usages);
        boolean test = path.startsWith("src/test/") || path.contains("/src/test/");
        return new FileIndex(path, packageName, test, imports, types, usages);
    }

    private static TypeInfo typeOf(
            String path, com.github.javaparser.ast.body.TypeDeclaration<?> declaration) {
        String qualified =
                declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
        String kind =
                declaration instanceof EnumDeclaration
                        ? "ENUM"
                        : declaration instanceof RecordDeclaration
                                ? "RECORD"
                                : declaration.isAnnotationDeclaration()
                                        ? "ANNOTATION"
                                        : declaration.isClassOrInterfaceDeclaration()
                                                        && declaration
                                                                .asClassOrInterfaceDeclaration()
                                                                .isInterface()
                                                ? "INTERFACE"
                                                : "CLASS";
        List<String> superTypes = new ArrayList<>();
        if (declaration.isClassOrInterfaceDeclaration()) {
            var type = declaration.asClassOrInterfaceDeclaration();
            type.getExtendedTypes().forEach(t -> superTypes.add(NameTypes.erase(t.asString())));
            type.getImplementedTypes().forEach(t -> superTypes.add(NameTypes.erase(t.asString())));
        } else if (declaration instanceof EnumDeclaration enumeration) {
            enumeration
                    .getImplementedTypes()
                    .forEach(t -> superTypes.add(NameTypes.erase(t.asString())));
        } else if (declaration instanceof RecordDeclaration record) {
            record.getImplementedTypes()
                    .forEach(t -> superTypes.add(NameTypes.erase(t.asString())));
        }
        List<FieldInfo> fields = new ArrayList<>();
        declaration
                .getFields()
                .forEach(
                        field ->
                                field.getVariables()
                                        .forEach(
                                                variable ->
                                                        fields.add(
                                                                new FieldInfo(
                                                                        variable.getNameAsString(),
                                                                        NameTypes.erase(
                                                                                variable.getType()
                                                                                        .asString()),
                                                                        line(variable),
                                                                        annotationNames(
                                                                                field
                                                                                        .getAnnotations()),
                                                                        annotationStrings(
                                                                                field
                                                                                        .getAnnotations()),
                                                                        false))));
        if (declaration instanceof EnumDeclaration enumeration) {
            for (EnumConstantDeclaration constant : enumeration.getEntries()) {
                fields.add(
                        new FieldInfo(
                                constant.getNameAsString(),
                                qualified,
                                line(constant),
                                annotationNames(constant.getAnnotations()),
                                annotationStrings(constant.getAnnotations()),
                                true));
            }
        }
        if (declaration instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                fields.add(
                        new FieldInfo(
                                component.getNameAsString(),
                                NameTypes.erase(component.getType().asString()),
                                line(component),
                                annotationNames(component.getAnnotations()),
                                annotationStrings(component.getAnnotations()),
                                false));
            }
        }
        List<MethodInfo> methods = new ArrayList<>();
        for (var method : declaration.getMethods()) {
            methods.add(
                    new MethodInfo(
                            method.getNameAsString(),
                            method.getParameters().stream()
                                    .map(p -> NameTypes.erase(p.getType().asString()))
                                    .toList(),
                            NameTypes.erase(method.getType().asString()),
                            line(method),
                            annotationNames(method.getAnnotations()),
                            false));
        }
        for (ConstructorDeclaration constructor : declaration.getConstructors()) {
            methods.add(
                    new MethodInfo(
                            "<init>",
                            constructor.getParameters().stream()
                                    .map(p -> NameTypes.erase(p.getType().asString()))
                                    .toList(),
                            "void",
                            line(constructor),
                            annotationNames(constructor.getAnnotations()),
                            true));
        }
        return new TypeInfo(
                qualified,
                declaration.getNameAsString(),
                kind,
                path,
                line(declaration),
                superTypes,
                annotationNames(declaration.getAnnotations()),
                fields,
                methods);
    }

    private static void collectUsages(
            String path, CompilationUnit unit, String[] lines, List<Usage> usages) {
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            String scope = call.getScope().map(Node::toString).orElse(null);
            usages.add(
                    usage(
                            UsageKind.METHOD_CALL,
                            call.getNameAsString(),
                            scope,
                            scopeType(call, call.getScope().orElse(null)),
                            call.getArguments().size(),
                            call,
                            path,
                            lines,
                            List.of()));
        }
        for (ObjectCreationExpr creation : unit.findAll(ObjectCreationExpr.class)) {
            usages.add(
                    usage(
                            UsageKind.CONSTRUCTOR_CALL,
                            NameTypes.erase(creation.getType().asString()),
                            null,
                            null,
                            creation.getArguments().size(),
                            creation,
                            path,
                            lines,
                            List.of()));
        }
        for (ClassOrInterfaceType type : unit.findAll(ClassOrInterfaceType.class)) {
            Node parent = type.getParentNode().orElse(null);
            if (parent instanceof ClassOrInterfaceType outer
                    && outer.getScope().map(s -> s == type).orElse(false)) {
                continue;
            }
            if (parent instanceof ObjectCreationExpr creation && creation.getType() == type) {
                continue;
            }
            usages.add(
                    usage(
                            UsageKind.TYPE_USE,
                            NameTypes.erase(type.asString()),
                            null,
                            null,
                            -1,
                            type,
                            path,
                            lines,
                            List.of()));
        }
        for (FieldAccessExpr access : unit.findAll(FieldAccessExpr.class)) {
            if (access.getParentNode().orElse(null) instanceof FieldAccessExpr parent
                    && parent.getScope() == access
                    && !startsUpper(access.getNameAsString())) {
                continue;
            }
            usages.add(
                    usage(
                            UsageKind.FIELD_ACCESS,
                            access.getNameAsString(),
                            access.getScope().toString(),
                            scopeType(access, access.getScope()),
                            -1,
                            access,
                            path,
                            lines,
                            List.of()));
        }
        for (SwitchEntry entry : unit.findAll(SwitchEntry.class)) {
            for (var label : entry.getLabels()) {
                if (label instanceof NameExpr name) {
                    usages.add(
                            usage(
                                    UsageKind.SWITCH_LABEL,
                                    name.getNameAsString(),
                                    null,
                                    null,
                                    -1,
                                    name,
                                    path,
                                    lines,
                                    List.of()));
                }
            }
        }
        for (MethodReferenceExpr reference : unit.findAll(MethodReferenceExpr.class)) {
            usages.add(
                    usage(
                            UsageKind.METHOD_REFERENCE,
                            reference.getIdentifier(),
                            reference.getScope().toString(),
                            scopeType(reference, reference.getScope()),
                            -1,
                            reference,
                            path,
                            lines,
                            List.of()));
        }
        for (AnnotationExpr annotation : unit.findAll(AnnotationExpr.class)) {
            List<String> values = new ArrayList<>();
            annotation.findAll(StringLiteralExpr.class).forEach(s -> values.add(cap(s.getValue())));
            usages.add(
                    usage(
                            UsageKind.ANNOTATION,
                            annotation.getNameAsString(),
                            null,
                            null,
                            -1,
                            annotation,
                            path,
                            lines,
                            values));
        }
        for (StringLiteralExpr literal : unit.findAll(StringLiteralExpr.class)) {
            addString(path, lines, usages, literal, literal.getValue());
        }
        for (TextBlockLiteralExpr block : unit.findAll(TextBlockLiteralExpr.class)) {
            addString(path, lines, usages, block, block.getValue());
        }
    }

    private static void addString(
            String path, String[] lines, List<Usage> usages, Node node, String value) {
        if (value.isEmpty() || value.length() > 2000) {
            return;
        }
        usages.add(
                usage(
                        UsageKind.STRING_LITERAL,
                        cap(value),
                        null,
                        null,
                        -1,
                        node,
                        path,
                        lines,
                        List.of()));
    }

    private static Usage usage(
            UsageKind kind,
            String name,
            String scope,
            String scopeType,
            int argCount,
            Node node,
            String path,
            String[] lines,
            List<String> values) {
        Range range = node.getRange().orElse(null);
        int line = range == null ? 0 : range.begin.line;
        int column = range == null ? 0 : range.begin.column;
        int endLine = range == null ? 0 : range.end.line;
        String snippet = line > 0 && line <= lines.length ? lines[line - 1].strip() : "";
        if (snippet.length() > MAX_SNIPPET) {
            snippet = snippet.substring(0, MAX_SNIPPET);
        }
        return new Usage(
                kind,
                name,
                scope,
                scopeType,
                argCount,
                new Location(path, line, column, endLine),
                enclosing(node),
                snippet,
                values);
    }

    private static String scopeType(Node origin, com.github.javaparser.ast.expr.Expression scope) {
        if (scope == null) {
            return null;
        }
        if (scope.isThisExpr()) {
            return enclosingType(origin);
        }
        if (scope instanceof NameExpr name) {
            String declared = NameTypes.lookup(origin, name.getNameAsString());
            if (declared != null) {
                return declared;
            }
            return startsUpper(name.getNameAsString()) ? name.getNameAsString() : null;
        }
        if (scope instanceof TypeExpr typeExpr) {
            String text = NameTypes.erase(typeExpr.getType().asString());
            String declared = NameTypes.lookup(origin, text);
            return declared != null ? declared : text;
        }
        if (scope instanceof FieldAccessExpr access) {
            if (access.getScope().isThisExpr()) {
                return NameTypes.lookup(origin, access.getNameAsString());
            }
            return startsUpper(access.getNameAsString()) ? access.toString() : null;
        }
        if (scope instanceof ObjectCreationExpr creation) {
            return NameTypes.erase(creation.getType().asString());
        }
        return null;
    }

    private static String enclosingType(Node node) {
        return node.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                .map(t -> t.getFullyQualifiedName().orElse(t.getNameAsString()).toString())
                .orElse(null);
    }

    private static String enclosing(Node node) {
        String type = enclosingType(node);
        if (type == null) {
            return "";
        }
        var method = node.findAncestor(MethodDeclaration.class);
        var constructor = node.findAncestor(ConstructorDeclaration.class);
        var initializer = node.findAncestor(InitializerDeclaration.class);
        if (method.isPresent()) {
            return type + "#" + method.get().getNameAsString();
        }
        if (constructor.isPresent()) {
            return type + "#<init>";
        }
        if (initializer.isPresent()) {
            return type + "#<clinit>";
        }
        return type;
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private static boolean startsUpper(String value) {
        return !value.isEmpty() && Character.isUpperCase(value.charAt(0));
    }

    private static String cap(String value) {
        return value.length() > MAX_STRING ? value.substring(0, MAX_STRING) : value;
    }

    private static List<String> annotationNames(List<AnnotationExpr> annotations) {
        return annotations.stream().map(AnnotationExpr::getNameAsString).toList();
    }

    private static List<String> annotationStrings(List<AnnotationExpr> annotations) {
        List<String> values = new ArrayList<>();
        for (AnnotationExpr annotation : annotations) {
            annotation.findAll(StringLiteralExpr.class).forEach(s -> values.add(cap(s.getValue())));
        }
        return values;
    }
}
