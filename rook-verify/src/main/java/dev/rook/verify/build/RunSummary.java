package dev.rook.verify.build;

import dev.rook.agent.loop.TestRun;
import dev.rook.verify.sandbox.SandboxResult;
import java.util.List;

public record RunSummary(Status status, TestReport report, String outputTail) {

    public enum Status {
        TESTS_RAN,
        NO_TESTS,
        COMPILE_FAILED,
        BUILD_FAILED,
        TIMED_OUT,
        OUT_OF_MEMORY
    }

    private static final int TAIL = 4000;

    public static RunSummary classify(SandboxResult result, TestReport report) {
        String tail = tail(result.combinedOutput());
        if (result.timedOut()) {
            return new RunSummary(Status.TIMED_OUT, report, tail);
        }
        if (result.oomKilled()) {
            return new RunSummary(Status.OUT_OF_MEMORY, report, tail);
        }
        if (!report.isEmpty() && report.executed() > 0) {
            return new RunSummary(Status.TESTS_RAN, report, tail);
        }
        String output = result.combinedOutput();
        if (output.contains("COMPILATION ERROR") || output.contains("Compilation failure")) {
            return new RunSummary(Status.COMPILE_FAILED, report, tail);
        }
        if (result.exitCode() != 0) {
            return new RunSummary(Status.BUILD_FAILED, report, tail);
        }
        return new RunSummary(Status.NO_TESTS, report, tail);
    }

    public boolean ranTests() {
        return status == Status.TESTS_RAN;
    }

    public TestRun toAgentRun() {
        return switch (status) {
            case TESTS_RAN -> {
                List<String> failures =
                        report.broken().stream()
                                .map(
                                        c ->
                                                c.message().isEmpty()
                                                        ? c.id()
                                                        : c.id() + ": " + c.message())
                                .toList();
                yield new TestRun(
                        true, report.executed(), failures.size(), failures, outputTail, false);
            }
            case NO_TESTS -> new TestRun(true, 0, 0, List.of(), outputTail, false);
            case COMPILE_FAILED -> TestRun.notCompiling(outputTail);
            case BUILD_FAILED ->
                    TestRun.notCompiling("the build failed before any test ran:\n" + outputTail);
            case OUT_OF_MEMORY ->
                    TestRun.notCompiling("the build ran out of memory:\n" + outputTail);
            case TIMED_OUT -> new TestRun(true, 0, 0, List.of(), outputTail, true);
        };
    }

    private static String tail(String text) {
        return text.length() <= TAIL ? text : text.substring(text.length() - TAIL);
    }
}
