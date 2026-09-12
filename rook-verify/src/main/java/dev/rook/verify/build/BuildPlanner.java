package dev.rook.verify.build;

import dev.rook.codeintel.project.RookConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BuildPlanner {

    public sealed interface Result {}

    public record Planned(BuildPlan plan) implements Result {}

    public record Unsupported(String reason) implements Result {}

    public static final String DEFAULT_IMAGE = "maven:3.9-eclipse-temurin-21";

    private static final String REPOSITORY = BuildPlan.CACHE_PATH + "/repository";

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
            test.removeIf(token -> token.equals("-o") || token.equals("--offline"));
            List<String> warm = new ArrayList<>(test);
            warm.add("-Dmaven.repo.local=" + REPOSITORY);
            test.add(1, "-o");
            test.add("-Dmaven.repo.local=" + REPOSITORY);
            return new Planned(plan(image, warm, test, timeout));
        }
        if (maven) {
            List<String> warm =
                    List.of("mvn", "-B", "-ntp", "test", "-Dmaven.repo.local=" + REPOSITORY);
            List<String> test =
                    List.of("mvn", "-B", "-ntp", "-o", "test", "-Dmaven.repo.local=" + REPOSITORY);
            return new Planned(plan(image, warm, test, timeout));
        }
        if (gradle) {
            return new Unsupported("gradle builds are not supported yet");
        }
        return new Unsupported("no pom.xml was found, only Maven builds are supported");
    }

    private static BuildPlan plan(
            String image, List<String> warm, List<String> test, Duration timeout) {
        return new BuildPlan(
                "maven",
                image,
                warm,
                test,
                Map.of("HOME", "/tmp", "MAVEN_OPTS", "-Xmx768m -Djava.io.tmpdir=/tmp"),
                timeout);
    }
}
