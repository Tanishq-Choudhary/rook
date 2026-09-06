package dev.rook.verify.build;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record BuildPlan(
        String tool,
        String image,
        List<String> prepareCommand,
        List<String> testCommand,
        List<String> descriptorFiles,
        Map<String, String> env,
        Duration timeout) {

    public static final String CACHE_PATH = "/cache";
    public static final String WORK_PATH = "/work";

    public BuildPlan {
        prepareCommand = List.copyOf(prepareCommand);
        testCommand = List.copyOf(testCommand);
        descriptorFiles = List.copyOf(descriptorFiles);
        env = Map.copyOf(env);
        if (testCommand.isEmpty()) {
            throw new IllegalArgumentException("a build plan needs a test command");
        }
    }

    public boolean needsPrepare() {
        return !prepareCommand.isEmpty();
    }
}
