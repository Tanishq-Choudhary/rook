package dev.rook.verify.build;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record BuildPlan(
        String tool,
        String image,
        List<String> warmCommand,
        List<String> testCommand,
        Map<String, String> env,
        Duration timeout) {

    public static final String CACHE_PATH = "/cache";
    public static final String SOURCE_PATH = "/src";
    public static final String WORK_PATH = "/work";

    public BuildPlan {
        warmCommand = List.copyOf(warmCommand);
        testCommand = List.copyOf(testCommand);
        env = Map.copyOf(env);
        if (testCommand.isEmpty() || warmCommand.isEmpty()) {
            throw new IllegalArgumentException("a build plan needs a warm up and a test command");
        }
    }

    public List<String> testCommandFor(String testFilter) {
        List<String> command = new ArrayList<>(testCommand);
        if (testFilter != null && !testFilter.isBlank()) {
            command.add("-Dtest=" + testFilter);
            command.add("-Dsurefire.failIfNoSpecifiedTests=false");
        }
        return command;
    }
}
