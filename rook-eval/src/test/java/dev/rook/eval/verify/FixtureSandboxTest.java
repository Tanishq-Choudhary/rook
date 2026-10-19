package dev.rook.eval.verify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.loop.TestRun;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.generate.FixtureGenerator;
import dev.rook.verify.sandbox.SandboxResult;
import dev.rook.verify.sandbox.SandboxRunner;
import dev.rook.verify.sandbox.SandboxSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureSandboxTest {

    @TempDir static Path shared;

    static Fixture fixture;

    @BeforeAll
    static void generate() {
        fixture =
                FixtureGenerator.generate(shared.resolve("fixtures")).stream()
                        .filter(f -> f.id().equals("method-rename-pay-field"))
                        .findFirst()
                        .orElseThrow();
    }

    private static SandboxResult result(boolean timedOut, String output) {
        return new SandboxResult(
                timedOut ? 124 : 0,
                timedOut,
                false,
                false,
                output,
                "",
                Duration.ofMillis(5),
                false);
    }

    private static SandboxRunner runner(
            SandboxResult outcome, String reportXml, List<SandboxSpec> seen) {
        return new SandboxRunner() {
            @Override
            public String kind() {
                return "stub";
            }

            @Override
            public SandboxResult run(SandboxSpec spec) {
                seen.add(spec);
                if (reportXml != null) {
                    try {
                        Path reports = Path.of(spec.workdir()).resolve("target/surefire-reports");
                        Files.createDirectories(reports);
                        Files.writeString(reports.resolve("TEST-app.AppTest.xml"), reportXml);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return outcome;
            }
        };
    }

    private TestRun run(SandboxRunner runner, Path temp) {
        return new FixtureSandbox(fixture, runner, temp.resolve("scratch"), Duration.ofMinutes(1))
                .run(fixture.repo(), "all");
    }

    @Test
    void passingReportsBecomeAPassingRun(@TempDir Path temp) {
        String xml =
                "<testsuite><testcase classname=\"app.AppTest\" name=\"one\"/>"
                        + "<testcase classname=\"app.AppTest\" name=\"two\"/>"
                        + "<testcase classname=\"app.AppTest\" name=\"three\"><skipped/></testcase></testsuite>";
        List<SandboxSpec> seen = new ArrayList<>();

        TestRun tests = run(runner(result(false, "ok"), xml, seen), temp);

        assertThat(tests.compiled()).isTrue();
        assertThat(tests.executed()).isEqualTo(2);
        assertThat(tests.failed()).isZero();
        assertThat(tests.passed()).isTrue();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).command()).contains("-o", "test");
    }

    @Test
    void failingCasesAreListedByName(@TempDir Path temp) {
        String xml =
                "<testsuite><testcase classname=\"app.AppTest\" name=\"one\"/>"
                        + "<testcase classname=\"app.AppTest\" name=\"two\">"
                        + "<failure message=\"boom\"/></testcase></testsuite>";

        TestRun tests = run(runner(result(false, "failed"), xml, new ArrayList<>()), temp);

        assertThat(tests.executed()).isEqualTo(2);
        assertThat(tests.failed()).isEqualTo(1);
        assertThat(tests.failures()).containsExactly("app.AppTest#two: boom");
        assertThat(tests.passed()).isFalse();
    }

    @Test
    void noReportMeansTheCodeDidNotCompile(@TempDir Path temp) {
        TestRun tests =
                run(
                        runner(
                                result(false, "[ERROR] cannot find symbol"),
                                null,
                                new ArrayList<>()),
                        temp);

        assertThat(tests.compiled()).isFalse();
        assertThat(tests.outputTail()).contains("cannot find symbol");
    }

    @Test
    void aTimeoutIsReportedAsOne(@TempDir Path temp) {
        TestRun tests = run(runner(result(true, "still running"), null, new ArrayList<>()), temp);

        assertThat(tests.timedOut()).isTrue();
        assertThat(tests.passed()).isFalse();
    }

    @Test
    void onlyTheLastLinesOfOutputAreKept(@TempDir Path temp) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            output.append("line ").append(i).append('\n');
        }

        TestRun tests =
                run(runner(result(false, output.toString()), null, new ArrayList<>()), temp);

        assertThat(tests.outputTail()).contains("line 199").doesNotContain("line 10\n");
        assertThat(tests.outputTail().lines().count()).isLessThanOrEqualTo(40);
    }
}
