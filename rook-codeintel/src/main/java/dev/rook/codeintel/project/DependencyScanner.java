package dev.rook.codeintel.project;

import dev.rook.codeintel.RepoFiles;
import dev.rook.core.error.RookException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DependencyScanner {

    private static final Pattern GRADLE =
            Pattern.compile(
                    "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor)\\s*\\(?\\s*[\"']([\\w.-]+):([\\w.-]+):([\\w.${}-]+)[\"']");

    private DependencyScanner() {}

    public record Scan(List<Dependency> dependencies, List<String> warnings, BuildTool buildTool) {}

    public enum BuildTool {
        MAVEN,
        GRADLE,
        UNKNOWN
    }

    public static Scan scan(Path root) {
        List<Dependency> all = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean maven = false;
        boolean gradle = false;
        for (RepoFiles.RepoFile file : RepoFiles.walk(root)) {
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            if (name.equals("pom.xml")) {
                maven = true;
                try {
                    String xml = RepoFiles.read(root, file.path(), 2 * 1024 * 1024);
                    all.addAll(PomParser.parse(xml, file.path()).dependencies());
                } catch (RookException e) {
                    warnings.add(file.path() + ": " + e.getMessage());
                }
            } else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                gradle = true;
                try {
                    String text = RepoFiles.read(root, file.path(), 2 * 1024 * 1024);
                    Matcher matcher = GRADLE.matcher(text);
                    while (matcher.find()) {
                        String version = matcher.group(4);
                        all.add(
                                new Dependency(
                                        matcher.group(2),
                                        matcher.group(3),
                                        version.contains("$") ? null : version,
                                        gradleScope(matcher.group(1)),
                                        file.path()));
                    }
                } catch (RookException e) {
                    warnings.add(file.path() + ": " + e.getMessage());
                }
            }
        }
        Map<String, Dependency> unique = new LinkedHashMap<>();
        for (Dependency dependency : all) {
            unique.merge(
                    dependency.coordinates() + "@" + dependency.declaredIn(),
                    dependency,
                    (a, b) -> a);
        }
        BuildTool tool = maven ? BuildTool.MAVEN : gradle ? BuildTool.GRADLE : BuildTool.UNKNOWN;
        return new Scan(List.copyOf(unique.values()), List.copyOf(warnings), tool);
    }

    private static String gradleScope(String configuration) {
        return switch (configuration) {
            case "testImplementation", "testRuntimeOnly" -> "test";
            case "compileOnly", "annotationProcessor" -> "provided";
            case "runtimeOnly" -> "runtime";
            default -> "compile";
        };
    }
}
