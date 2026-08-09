package dev.rook.codeintel.project;

import dev.rook.codeintel.RepoFiles;
import dev.rook.core.error.RookException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public record RepoProfile(
        RookConfig config,
        DependencyScanner.Scan scan,
        List<String> mainRoots,
        List<String> testRoots) {

    public static RepoProfile detect(Path root) {
        RookConfig config = RookConfig.defaults();
        Path configFile = root.resolve(".rook.yml");
        if (Files.isRegularFile(configFile)) {
            config = RookConfig.parse(RepoFiles.read(root, ".rook.yml", 64 * 1024));
        }
        DependencyScanner.Scan scan = DependencyScanner.scan(root);
        TreeSet<String> main = new TreeSet<>();
        TreeSet<String> test = new TreeSet<>();
        for (RepoFiles.RepoFile file : RepoFiles.walk(root)) {
            if (!file.path().endsWith(".java")) {
                continue;
            }
            collect(file.path(), "src/main/java", main);
            collect(file.path(), "src/test/java", test);
        }
        return new RepoProfile(config, scan, filter(main, config), filter(test, config));
    }

    public boolean watches(String coordinates) {
        return config.watch().stream().anyMatch(w -> coordinates.equals(w.coordinates()));
    }

    public List<Dependency> dependencyOn(String coordinates) {
        return scan.dependencies().stream()
                .filter(d -> d.coordinates().equals(coordinates))
                .toList();
    }

    private static void collect(String path, String marker, TreeSet<String> roots) {
        int index = path.indexOf(marker + "/");
        if (index >= 0 && (index == 0 || path.charAt(index - 1) == '/')) {
            roots.add(path.substring(0, index + marker.length()));
        }
    }

    private static List<String> filter(TreeSet<String> roots, RookConfig config) {
        List<String> kept = new ArrayList<>();
        for (String root : roots) {
            boolean excluded =
                    config.paths().exclude().stream()
                            .anyMatch(e -> root.equals(trim(e)) || root.startsWith(trim(e) + "/"));
            boolean included =
                    config.paths().include().isEmpty()
                            || config.paths().include().stream()
                                    .anyMatch(
                                            i ->
                                                    root.equals(trim(i))
                                                            || root.startsWith(trim(i) + "/"));
            if (included && !excluded) {
                kept.add(root);
            }
        }
        if (kept.size() > 200) {
            throw RookException.invalid("repository has more than 200 source roots");
        }
        return List.copyOf(kept);
    }

    private static String trim(String value) {
        String normalized = value.replace('\\', '/');
        return normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
