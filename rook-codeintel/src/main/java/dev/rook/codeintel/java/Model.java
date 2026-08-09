package dev.rook.codeintel.java;

import java.util.List;

public final class Model {

    private Model() {}

    public enum UsageKind {
        METHOD_CALL,
        CONSTRUCTOR_CALL,
        TYPE_USE,
        FIELD_ACCESS,
        SWITCH_LABEL,
        METHOD_REFERENCE,
        ANNOTATION,
        STRING_LITERAL
    }

    public record Location(String file, int line, int column, int endLine) {}

    public record Usage(
            UsageKind kind,
            String name,
            String scope,
            String scopeType,
            int argCount,
            Location location,
            String enclosing,
            String snippet,
            List<String> values) {

        public Usage {
            values = values == null ? List.of() : List.copyOf(values);
        }

        public String simpleName() {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
    }

    public record FieldInfo(
            String name,
            String type,
            int line,
            List<String> annotations,
            List<String> annotationValues,
            boolean enumConstant) {

        public FieldInfo {
            annotations = List.copyOf(annotations);
            annotationValues = List.copyOf(annotationValues);
        }
    }

    public record MethodInfo(
            String name,
            List<String> parameterTypes,
            String returnType,
            int line,
            List<String> annotations,
            boolean constructor) {

        public MethodInfo {
            parameterTypes = List.copyOf(parameterTypes);
            annotations = List.copyOf(annotations);
        }
    }

    public record TypeInfo(
            String qualifiedName,
            String simpleName,
            String kind,
            String file,
            int line,
            List<String> superTypes,
            List<String> annotations,
            List<FieldInfo> fields,
            List<MethodInfo> methods) {

        public TypeInfo {
            superTypes = List.copyOf(superTypes);
            annotations = List.copyOf(annotations);
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
        }
    }

    public record ImportInfo(String name, boolean isStatic, boolean wildcard, int line) {}

    public record FileIndex(
            String path,
            String packageName,
            boolean test,
            List<ImportInfo> imports,
            List<TypeInfo> types,
            List<Usage> usages) {

        public FileIndex {
            imports = List.copyOf(imports);
            types = List.copyOf(types);
            usages = List.copyOf(usages);
        }

        public boolean imports(String qualifiedName) {
            return imports.stream()
                    .anyMatch(
                            i ->
                                    !i.isStatic()
                                            && (i.name().equals(qualifiedName)
                                                    || (i.wildcard()
                                                            && qualifiedName.startsWith(
                                                                    i.name() + ".")
                                                            && qualifiedName.indexOf(
                                                                            '.',
                                                                            i.name().length() + 1)
                                                                    < 0)));
        }
    }
}
