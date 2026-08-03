package dev.rook.changes.jar;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ApiSurface(Map<String, ClassApi> classes) {

    public ApiSurface {
        classes = classes == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(classes));
    }

    public record ClassApi(
            String name,
            int access,
            String superName,
            List<String> interfaces,
            boolean deprecated,
            Map<String, MethodApi> methods,
            Map<String, FieldApi> fields) {

        public ClassApi {
            interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
            methods =
                    methods == null
                            ? Map.of()
                            : Collections.unmodifiableMap(new TreeMap<>(methods));
            fields = fields == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(fields));
        }

        public String simpleName() {
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
    }

    public record MethodApi(
            String name,
            String descriptor,
            int access,
            boolean deprecated,
            boolean forRemoval,
            List<String> parameterTypes,
            String returnType) {

        public MethodApi {
            parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        }

        public String key() {
            return name + descriptor;
        }

        public boolean isConstructor() {
            return "<init>".equals(name);
        }
    }

    public record FieldApi(
            String name, String descriptor, int access, boolean deprecated, String type) {

        private static final int ACC_ENUM = 0x4000;

        public boolean isEnumConstant() {
            return (access & ACC_ENUM) != 0;
        }
    }
}
