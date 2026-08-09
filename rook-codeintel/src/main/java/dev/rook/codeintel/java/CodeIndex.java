package dev.rook.codeintel.java;

import com.github.javaparser.ast.CompilationUnit;
import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.TypeInfo;
import dev.rook.codeintel.java.Model.Usage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class CodeIndex {

    private final Path root;
    private final Map<String, FileIndex> files;
    private final Map<String, CompilationUnit> units;
    private final Map<String, TypeInfo> types;
    private final List<String> problems;

    CodeIndex(
            Path root,
            Map<String, FileIndex> files,
            Map<String, CompilationUnit> units,
            List<String> problems) {
        this.root = root;
        this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        this.units = Map.copyOf(units);
        this.problems = List.copyOf(problems);
        Map<String, TypeInfo> byName = new LinkedHashMap<>();
        for (FileIndex file : files.values()) {
            for (TypeInfo type : file.types()) {
                byName.putIfAbsent(type.qualifiedName(), type);
            }
        }
        this.types = Collections.unmodifiableMap(byName);
    }

    public Path root() {
        return root;
    }

    public List<FileIndex> files() {
        return new ArrayList<>(files.values());
    }

    public Optional<FileIndex> file(String path) {
        return Optional.ofNullable(files.get(path));
    }

    public Optional<CompilationUnit> unit(String path) {
        return Optional.ofNullable(units.get(path));
    }

    public Map<String, TypeInfo> types() {
        return types;
    }

    public Optional<TypeInfo> type(String qualifiedName) {
        return Optional.ofNullable(types.get(qualifiedName));
    }

    public List<TypeInfo> typesBySimpleName(String simpleName) {
        return types.values().stream().filter(t -> t.simpleName().equals(simpleName)).toList();
    }

    public Stream<Usage> usages() {
        return files.values().stream().flatMap(f -> f.usages().stream());
    }

    public List<String> problems() {
        return problems;
    }
}
