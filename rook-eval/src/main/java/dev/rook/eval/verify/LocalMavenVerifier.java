package dev.rook.eval.verify;

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
import java.util.Set;

public final class LocalMavenVerifier implements FixtureVerifier {

    private final SandboxRunner runner;
    private final Path scratch;
    private final Duration timeout;
    private final List<String> maven;

    public LocalMavenVerifier(SandboxRunner runner, Path scratch, Duration timeout) {
        this.runner = runner;
        this.scratch = scratch;
        this.timeout = timeout;
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        this.maven = List.of(windows ? "mvn.cmd" : "mvn", "-B", "-ntp", "-o", "test");
    }

    @Override
    public String name() {
        return "local-maven";
    }

    @Override
    public Verdict verify(Fixture fixture, Path repoRoot, SdkVersion sdk) {
        Path project =
                FixtureProject.assemble(
                        fixture,
                        repoRoot,
                        sdk,
                        scratch.resolve(fixture.id() + "-" + sdk.name().toLowerCase(Locale.ROOT)));
        SandboxSpec spec = SandboxSpec.of("local", project.toString(), maven).withTimeout(timeout);
        SandboxResult result = runner.run(spec);
        TestReport report = JUnitReports.readAll(project);
        long millis = result.duration().toMillis();
        if (report.isEmpty()) {
            return Verdict.notCompiling(millis, errors(result.combinedOutput()));
        }
        Set<String> independent = FixtureProject.independentClasses(fixture);
        List<String> visibleFailures = new ArrayList<>();
        List<String> independentFailures = new ArrayList<>();
        int visibleRun = 0;
        int independentRun = 0;
        for (TestCaseResult test : report.cases().values()) {
            if (test.outcome() == TestCaseResult.Outcome.SKIPPED) {
                continue;
            }
            boolean hidden = independent.contains(test.id().substring(0, test.id().indexOf('#')));
            if (hidden) {
                independentRun++;
            } else {
                visibleRun++;
            }
            if (test.broken()) {
                (hidden ? independentFailures : visibleFailures).add(test.id());
            }
        }
        return new Verdict(
                true,
                visibleRun,
                visibleFailures,
                independentRun,
                independentFailures,
                millis,
                result.timedOut() ? "timed out" : "");
    }

    private static String errors(String output) {
        return String.join(
                "\n", output.lines().filter(line -> line.contains("ERROR")).limit(8).toList());
    }
}
