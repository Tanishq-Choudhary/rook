package dev.rook.verify.build;

import dev.rook.codeintel.project.RookConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class BuildPlanner {

    public sealed interface Result {}

    public record Planned(BuildPlan plan) implements Result {}

    public record Unsupported(String reason) implements Result {}

    public static final String DEFAULT_IMAGE = "maven:3.9-eclipse-temurin-21";

    private static final String REPOSITORY = BuildPlan.CACHE_PATH + "/repository";
    private static final int MAX_DESCRIPTORS = 200;

    private BuildPlanner() {}

    public static Result detect(Path root, RookConfig.Verify verify, String image) {
        boolean maven = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradle =
                Files.isRegularFile(root.resolve("build.gradle"))
                        || Files.isRegularFile(root.resolve("build.gradle.kts"));
        Duration timeout = Duration.ofSeconds(verify.timeoutSeconds());
        if (verify.command() != null) {
            List<String> command = CommandLine.parse(verify.command());
            if (command.get(0).contains("gradle")) {
                return new Unsupported("gradle builds are not supported yet");
            }
            List<String> test = new ArrayList<>(command);
            if (!test.contains("-o") && !test.contains("--offline")) {
                test.add(1, "-o");
            }
            test.add("-Dmaven.repo.local=" + REPOSITORY);
            return new Planned(plan("maven", image, maven, root, test, timeout));
        }
        if (maven) {
            List<String> test =
                    List.of("mvn", "-B", "-ntp", "-o", "test", "-Dmaven.repo.local=" + REPOSITORY);
            return new Planned(plan("maven", image, true, root, test, timeout));
        }
        if (gradle) {
            return new Unsupported("gradle builds are not supported yet");
        }
        return new Unsupported("no pom.xml was found, only Maven builds are supported");
    }

    private static BuildPlan plan(
            String tool,
            String image,
            boolean maven,
            Path root,
            List<String> test,
            Duration timeout) {
        List<String> prepare =
                maven
                        ? List.of(
                                "mvn",
                                "-B",
                                "-ntp",
                                "dependency:go-offline",
                                "-Dmaven.repo.local=" + REPOSITORY)
                        : List.of();
        return new BuildPlan(
                tool,
                image,
                prepare,
                test,
                maven ? descriptors(root) : List.of(),
                Map.of("HOME", "/tmp", "MAVEN_OPTS", "-Xmx512m -Djava.io.tmpdir=/tmp"),
                timeout);
    }

    static List<String> descriptors(Path root) {
        TreeSet<String> found = new TreeSet<>();
        try {
            Files.walkFileTree(
                    root,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            String name =
                                    dir.getFileName() == null ? "" : dir.getFileName().toString();
                            boolean skip =
                                    !dir.equals(root)
                                            && (name.equals("target")
                                                    || name.equals(".git")
                                                    || name.equals("node_modules"));
                            return skip
                                    ? java.nio.file.FileVisitResult.SKIP_SUBTREE
                                    : java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(
                                Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString();
                            if (attrs.isRegularFile()
                                    && name.equals("pom.xml")
                                    && found.size() < MAX_DESCRIPTORS) {
                                found.add(root.relativize(file).toString().replace('\\', '/'));
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(found);
    }
}
