package dev.rook.changes.jar;

import dev.rook.changes.jar.ApiSurface.ClassApi;
import dev.rook.changes.jar.ApiSurface.FieldApi;
import dev.rook.changes.jar.ApiSurface.MethodApi;
import dev.rook.core.error.RookException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class ApiSurfaceReader {

    private static final int MAX_CLASS_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CLASSES = 200_000;
    private static final String DEPRECATED = "Ljava/lang/Deprecated;";

    private ApiSurfaceReader() {}

    public static ApiSurface read(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            return read(in);
        } catch (IOException e) {
            throw RookException.invalid("cannot read jar " + jar.getFileName(), e);
        }
    }

    public static ApiSurface read(InputStream jar) {
        Map<String, ClassApi> classes = new TreeMap<>();
        try (JarInputStream in = new JarInputStream(jar)) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()
                        || !name.endsWith(".class")
                        || name.startsWith("META-INF/")
                        || name.endsWith("module-info.class")
                        || name.endsWith("package-info.class")) {
                    continue;
                }
                if (classes.size() >= MAX_CLASSES) {
                    throw RookException.invalid("jar contains too many classes");
                }
                byte[] bytes = readBounded(in);
                SurfaceVisitor visitor = new SurfaceVisitor();
                new ClassReader(bytes)
                        .accept(
                                visitor,
                                ClassReader.SKIP_CODE
                                        | ClassReader.SKIP_DEBUG
                                        | ClassReader.SKIP_FRAMES);
                ClassApi api = visitor.result();
                if (api != null) {
                    classes.put(api.name(), api);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw RookException.invalid("jar is not readable", e);
        }
        return new ApiSurface(classes);
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
        if (bytes.length > MAX_CLASS_BYTES) {
            throw RookException.invalid("class file larger than " + MAX_CLASS_BYTES + " bytes");
        }
        return bytes;
    }

    static String sourceName(String internalName) {
        return internalName.replace('/', '.').replace('$', '.');
    }

    static String typeName(Type type) {
        return type.getClassName().replace('$', '.');
    }

    private static boolean visible(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                && (access & Opcodes.ACC_SYNTHETIC) == 0;
    }

    private static final class SurfaceVisitor extends ClassVisitor {

        private String name;
        private int access;
        private int effectiveAccess;
        private String superName;
        private List<String> interfaces = List.of();
        private boolean deprecated;
        private boolean anonymous;
        private final Map<String, MethodApi> methods = new TreeMap<>();
        private final Map<String, FieldApi> fields = new TreeMap<>();
        private String internalName;

        SurfaceVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces) {
            this.internalName = name;
            this.name = sourceName(name);
            this.access = access;
            this.effectiveAccess = access;
            this.superName =
                    superName == null || "java/lang/Object".equals(superName)
                            ? null
                            : sourceName(superName);
            this.interfaces =
                    interfaces == null
                            ? List.of()
                            : Arrays.stream(interfaces).map(ApiSurfaceReader::sourceName).toList();
            this.deprecated = (access & Opcodes.ACC_DEPRECATED) != 0;
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            if (name.equals(internalName)) {
                effectiveAccess = access;
                if (innerName == null) {
                    anonymous = true;
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (DEPRECATED.equals(descriptor)) {
                deprecated = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if (!visible(access) || (access & Opcodes.ACC_BRIDGE) != 0 || "<clinit>".equals(name)) {
                return null;
            }
            Type[] arguments = Type.getArgumentTypes(descriptor);
            List<String> parameters = new ArrayList<>();
            for (Type argument : arguments) {
                parameters.add(typeName(argument));
            }
            String returnType = typeName(Type.getReturnType(descriptor));
            boolean deprecatedFlag = (access & Opcodes.ACC_DEPRECATED) != 0;
            return new MethodVisitor(Opcodes.ASM9) {
                private boolean marked = deprecatedFlag;
                private boolean forRemoval;

                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (DEPRECATED.equals(annotation)) {
                        marked = true;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String key, Object value) {
                                if ("forRemoval".equals(key) && Boolean.TRUE.equals(value)) {
                                    forRemoval = true;
                                }
                            }
                        };
                    }
                    return null;
                }

                @Override
                public void visitEnd() {
                    MethodApi api =
                            new MethodApi(
                                    name,
                                    descriptor,
                                    access,
                                    marked,
                                    forRemoval,
                                    parameters,
                                    returnType);
                    methods.put(api.key(), api);
                }
            };
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            if (!visible(access)) {
                return null;
            }
            boolean deprecatedFlag = (access & Opcodes.ACC_DEPRECATED) != 0;
            String type = typeName(Type.getType(descriptor));
            return new FieldVisitor(Opcodes.ASM9) {
                private boolean marked = deprecatedFlag;

                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (DEPRECATED.equals(annotation)) {
                        marked = true;
                    }
                    return null;
                }

                @Override
                public void visitEnd() {
                    fields.put(name, new FieldApi(name, descriptor, access, marked, type));
                }
            };
        }

        ClassApi result() {
            if (anonymous || !visible(effectiveAccess)) {
                return null;
            }
            return new ClassApi(name, access, superName, interfaces, deprecated, methods, fields);
        }
    }
}
