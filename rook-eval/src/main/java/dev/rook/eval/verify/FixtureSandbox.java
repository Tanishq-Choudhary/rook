package dev.rook.eval.verify;

import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.loop.TestRun;
import dev.rook.eval.fixture.Fixture;
import dev.rook.verify.build.JUnitReports;
import dev.rook.verify.build.TestCaseResult;
import dev.rook.verify.build.TestReport;
import dev.rook.verify.sandbox.SandboxResult;
import dev.rook.verify.sandbox.SandboxRunner;
import dev.rook.verify.sandbox.SandboxSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FixtureSandbox implements SandboxPort {

    private final Fixture fixture;
    private final SandboxRunner runner;
    private final Path scratch;
    private final Duration timeout;
    private final List<String> maven;

    public FixtureSandbox(Fixture fixture, SandboxRunner runner, Path scratch, Duration timeout) {
        this.fixture = fixture;
        this.runner = runner;
        this.scratch = scratch;
        this.timeout = timeout;
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        this.maven = List.of(windows ? "mvn.cmd" : "mvn", "-B", "-ntp", "-o", "test");
    }

    @Override
    public TestRun run(Path workspaceRoot, String scope) {
        Path project =
                FixtureProject.assemble(
                        fixture,
                        workspaceRoot,
                        SdkVersion.AFTER,
                        scratch.resolve(fixture.id() + "-agent"));
        SandboxResult result =
                runner.run(SandboxSpec.of("local", project.toString(), maven).withTimeout(timeout));
        TestReport report = JUnitReports.readAll(project);
        if (result.timedOut()) {
            return new TestRun(true, 0, 0, List.of(), tail(result.combinedOutput()), true);
        }
        if (report.isEmpty()) {
            return TestRun.notCompiling(tail(result.combinedOutput()));
        }
        List<String> failures = new ArrayList<>();
        int executed = 0;
        for (TestCaseResult test : report.cases().values()) {
            if (test.outcome() == TestCaseResult.Outcome.SKIPPED) {
                continue;
            }
            executed++;
            if (test.broken()) {
                failures.add(
                        test.message() == null || test.message().isEmpty()
                                ? test.id()
                                : test.id() + ": " + test.message());
            }
        }
        return new TestRun(
                true, executed, failures.size(), failures, tail(result.combinedOutput()), false);
    }

    private static String tail(String output) {
        String[] lines = output.split("\\R");
        int from = Math.max(0, lines.length - 40);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
