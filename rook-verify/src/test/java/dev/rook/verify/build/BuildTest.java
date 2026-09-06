package dev.rook.verify.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.loop.TestRun;
import dev.rook.codeintel.project.RookConfig;
import dev.rook.core.error.RookException;
import dev.rook.verify.sandbox.SandboxResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuildTest {

    @TempDir Path temp;

    private static final RookConfig.Verify DEFAULT = new RookConfig.Verify(null, null);

    private void write(String relative, String content) throws IOException {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private BuildPlan plan(RookConfig.Verify verify) {
        BuildPlanner.Result result = BuildPlanner.detect(temp, verify, BuildPlanner.DEFAULT_IMAGE);
        assertThat(result).isInstanceOf(BuildPlanner.Planned.class);
        return ((BuildPlanner.Planned) result).plan();
    }

    @Test
    void aMavenProjectGetsOfflineTestsAndAPrepareStep() throws IOException {
        write("pom.xml", "<project/>");
        write("module/pom.xml", "<project/>");
        write("module/target/pom.xml", "<project/>");
        BuildPlan plan = plan(DEFAULT);
        assertThat(plan.tool()).isEqualTo("maven");
        assertThat(plan.testCommand())
                .containsExactly(
                        "mvn", "-B", "-ntp", "-o", "test", "-Dmaven.repo.local=/cache/repository");
        assertThat(plan.needsPrepare()).isTrue();
        assertThat(plan.prepareCommand()).contains("dependency:go-offline");
        assertThat(plan.descriptorFiles()).containsExactly("module/pom.xml", "pom.xml");
        assertThat(plan.timeout()).isEqualTo(Duration.ofSeconds(600));
        assertThat(plan.env()).containsKey("HOME");
        assertThat(plan.image()).isEqualTo(BuildPlanner.DEFAULT_IMAGE);
    }

    @Test
    void aConfiguredCommandIsValidatedMadeOfflineAndPinnedToTheCache() throws IOException {
        write("pom.xml", "<project/>");
        BuildPlan plan = plan(new RookConfig.Verify("mvn -B verify -Dtest=FooTest", 120));
        assertThat(plan.testCommand())
                .containsExactly(
                        "mvn",
                        "-o",
                        "-B",
                        "verify",
                        "-Dtest=FooTest",
                        "-Dmaven.repo.local=/cache/repository");
        assertThat(plan.timeout()).isEqualTo(Duration.ofSeconds(120));
        BuildPlan already = plan(new RookConfig.Verify("mvn --offline test", null));
        assertThat(already.testCommand()).doesNotContain("-o");
    }

    @Test
    void gradleAndUnknownBuildsAreDeclined() throws IOException {
        write("build.gradle", "plugins {}");
        assertThat(BuildPlanner.detect(temp, DEFAULT, "img"))
                .isEqualTo(new BuildPlanner.Unsupported("gradle builds are not supported yet"));
        assertThat(BuildPlanner.detect(temp, new RookConfig.Verify("./gradlew test", null), "img"))
                .isInstanceOf(BuildPlanner.Unsupported.class);
        Path empty = temp.resolve("empty");
        assertThat(BuildPlanner.detect(empty, DEFAULT, "img"))
                .isEqualTo(
                        new BuildPlanner.Unsupported(
                                "no pom.xml was found, only Maven builds are supported"));
    }

    @Test
    void validCommandsAreTokenised() {
        assertThat(CommandLine.parse("  mvn -B   test  -Dtest=AppTest "))
                .containsExactly("mvn", "-B", "test", "-Dtest=AppTest");
        assertThat(CommandLine.parse("./mvnw -pl a,b test"))
                .containsExactly("./mvnw", "-pl", "a,b", "test");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "mvn test; rm -rf /",
                "mvn test && curl evil.test",
                "mvn test | tee out",
                "mvn `id`",
                "mvn $(id)",
                "mvn test > out.txt",
                "bash -c mvn",
                "rm -rf /",
                "mvn -s settings.xml test",
                "mvn --settings=x test",
                "mvn -gs g.xml test",
                "mvn -t toolchains.xml test",
                "mvn test -Dmaven.repo.local=/tmp/x",
                "mvn test -DMAVEN.REPO.LOCAL=/tmp/x"
            })
    void unsafeCommandsAreRejected(String command) {
        assertThatThrownBy(() -> CommandLine.parse(command)).isInstanceOf(RookException.class);
    }

    @Test
    void junitReportsAreParsedPerTestCase() {
        String xml =
                """
                <testsuite name="a.AppTest" tests="4">
                  <testcase classname="a.AppTest" name="passes" time="0.01"/>
                  <testcase classname="a.AppTest" name="fails" time="0.02"><failure message="expected 1 but was 2" type="AssertionError">stack</failure></testcase>
                  <testcase classname="a.AppTest" name="errors" time="0.03"><error type="NullPointerException">NPE here
                  more</error></testcase>
                  <testcase classname="a.AppTest" name="skipped"><skipped/></testcase>
                </testsuite>
                """;
        List<TestCaseResult> results = JUnitReports.parse(xml);
        assertThat(results).hasSize(4);
        TestReport report = TestReport.of(results);
        assertThat(report.executed()).isEqualTo(3);
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.broken())
                .extracting(TestCaseResult::id)
                .containsExactly("a.AppTest#errors", "a.AppTest#fails");
        assertThat(report.cases().get("a.AppTest#fails").message())
                .isEqualTo("expected 1 but was 2");
        assertThat(report.cases().get("a.AppTest#errors").message()).isEqualTo("NPE here");
        assertThat(report.cases().get("a.AppTest#skipped").outcome())
                .isEqualTo(TestCaseResult.Outcome.SKIPPED);
        assertThat(report.cases().get("a.AppTest#passes").seconds()).isEqualTo(0.01);
    }

    @Test
    void wrapperElementsAndOddCasesAreHandled() {
        String xml =
                "<testsuites><testsuite><testcase name=\"loose\" time=\"x\"/><testcase classname=\"c\" name=\"\"/></testsuite></testsuites>";
        List<TestCaseResult> results = JUnitReports.parse(xml);
        assertThat(results)
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.id()).isEqualTo("loose");
                            assertThat(r.seconds()).isZero();
                        });
    }

    @Test
    void xmlWithADoctypeIsRefused() {
        String xml = "<!DOCTYPE x [<!ENTITY a \"b\">]><testsuite/>";
        assertThatThrownBy(() -> JUnitReports.parse(xml)).isInstanceOf(RookException.class);
    }

    @Test
    void longMessagesAreCut() {
        String xml =
                "<testsuite><testcase classname=\"c\" name=\"n\"><failure message=\""
                        + "x".repeat(2000)
                        + "\"/></testcase></testsuite>";
        assertThat(JUnitReports.parse(xml).get(0).message()).hasSize(500);
    }

    @Test
    void reportsAreCollectedFromBuildOutputFolders() throws IOException {
        write(
                "a/target/surefire-reports/TEST-a.AppTest.xml",
                "<testsuite><testcase classname=\"a.AppTest\" name=\"x\"/></testsuite>");
        write(
                "b/target/failsafe-reports/TEST-b.ItTest.xml",
                "<testsuite><testcase classname=\"b.ItTest\" name=\"y\"><failure message=\"m\"/></testcase></testsuite>");
        write(
                "c/src/TEST-ignored.xml",
                "<testsuite><testcase classname=\"c\" name=\"z\"/></testsuite>");
        write("d/target/surefire-reports/TEST-broken.xml", "not xml at all");
        write("d/target/surefire-reports/other.xml", "<testsuite/>");
        TestReport report = JUnitReports.readAll(temp);
        assertThat(report.cases().keySet()).containsExactly("a.AppTest#x", "b.ItTest#y");
        assertThat(report.broken()).hasSize(1);
        assertThat(JUnitReports.readAll(temp.resolve("nowhere")).isEmpty()).isTrue();
    }

    @Test
    void theWorstOutcomeWinsWhenATestAppearsTwice() {
        TestReport report =
                TestReport.of(
                        List.of(
                                new TestCaseResult("t#a", TestCaseResult.Outcome.PASSED, "", 0),
                                new TestCaseResult("t#a", TestCaseResult.Outcome.FAILED, "x", 0),
                                new TestCaseResult("t#a", TestCaseResult.Outcome.SKIPPED, "", 0),
                                new TestCaseResult("t#b", TestCaseResult.Outcome.FAILED, "", 0),
                                new TestCaseResult("t#b", TestCaseResult.Outcome.ERROR, "e", 0)));
        assertThat(report.cases().get("t#a").outcome()).isEqualTo(TestCaseResult.Outcome.FAILED);
        assertThat(report.cases().get("t#b").outcome()).isEqualTo(TestCaseResult.Outcome.ERROR);
        assertThat(TestReport.empty().executed()).isZero();
    }

    private static SandboxResult result(int exit, boolean timedOut, boolean oom, String stdout) {
        return new SandboxResult(exit, timedOut, oom, false, stdout, "", Duration.ZERO);
    }

    private static TestReport report(TestCaseResult.Outcome... outcomes) {
        List<TestCaseResult> list = new ArrayList<>();
        for (int i = 0; i < outcomes.length; i++) {
            list.add(new TestCaseResult("t#" + i, outcomes[i], "boom " + i, 0));
        }
        return TestReport.of(list);
    }

    @Test
    void aRunWithTestsIsClassifiedAndConvertedForTheAgent() {
        RunSummary summary =
                RunSummary.classify(
                        result(1, false, false, "BUILD FAILURE"),
                        report(TestCaseResult.Outcome.PASSED, TestCaseResult.Outcome.FAILED));
        assertThat(summary.status()).isEqualTo(RunSummary.Status.TESTS_RAN);
        assertThat(summary.ranTests()).isTrue();
        TestRun run = summary.toAgentRun();
        assertThat(run.compiled()).isTrue();
        assertThat(run.executed()).isEqualTo(2);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.failures()).containsExactly("t#1: boom 1");
        assertThat(run.passed()).isFalse();
        assertThat(
                        RunSummary.classify(
                                        result(0, false, false, ""),
                                        report(TestCaseResult.Outcome.PASSED))
                                .toAgentRun()
                                .passed())
                .isTrue();
    }

    @Test
    void failuresWithoutMessagesShowJustTheTestId() {
        TestReport report =
                TestReport.of(
                        List.of(new TestCaseResult("t#a", TestCaseResult.Outcome.ERROR, "", 0)));
        assertThat(RunSummary.classify(result(1, false, false, ""), report).toAgentRun().failures())
                .containsExactly("t#a");
    }

    @Test
    void everyKindOfNonRunIsDistinguished() {
        TestReport none = TestReport.empty();
        assertThat(RunSummary.classify(result(0, true, false, ""), none).status())
                .isEqualTo(RunSummary.Status.TIMED_OUT);
        assertThat(RunSummary.classify(result(137, false, true, ""), none).status())
                .isEqualTo(RunSummary.Status.OUT_OF_MEMORY);
        assertThat(
                        RunSummary.classify(
                                        result(1, false, false, "[ERROR] COMPILATION ERROR : x"),
                                        none)
                                .status())
                .isEqualTo(RunSummary.Status.COMPILE_FAILED);
        assertThat(
                        RunSummary.classify(result(1, false, false, "Compilation failure"), none)
                                .status())
                .isEqualTo(RunSummary.Status.COMPILE_FAILED);
        assertThat(
                        RunSummary.classify(
                                        result(1, false, false, "Could not resolve dependencies"),
                                        none)
                                .status())
                .isEqualTo(RunSummary.Status.BUILD_FAILED);
        assertThat(RunSummary.classify(result(0, false, false, "BUILD SUCCESS"), none).status())
                .isEqualTo(RunSummary.Status.NO_TESTS);
        assertThat(
                        RunSummary.classify(
                                        result(0, false, false, ""),
                                        report(TestCaseResult.Outcome.SKIPPED))
                                .status())
                .isEqualTo(RunSummary.Status.NO_TESTS);
    }

    @Test
    void nonRunsConvertToAgentRunsThatCannotPass() {
        TestReport none = TestReport.empty();
        TestRun timeout = RunSummary.classify(result(0, true, false, ""), none).toAgentRun();
        assertThat(timeout.timedOut()).isTrue();
        assertThat(timeout.passed()).isFalse();
        TestRun compile =
                RunSummary.classify(result(1, false, false, "COMPILATION ERROR"), none)
                        .toAgentRun();
        assertThat(compile.compiled()).isFalse();
        assertThat(compile.describe()).contains("does not compile");
        TestRun build = RunSummary.classify(result(1, false, false, "offline"), none).toAgentRun();
        assertThat(build.describe()).contains("before any test ran");
        TestRun oom = RunSummary.classify(result(137, false, true, ""), none).toAgentRun();
        assertThat(oom.describe()).contains("ran out of memory");
        TestRun noTests = RunSummary.classify(result(0, false, false, ""), none).toAgentRun();
        assertThat(noTests.passed()).isFalse();
        assertThat(noTests.describe()).contains("no tests were executed");
    }

    @Test
    void theOutputTailKeepsTheEnd() {
        String padded = "a".repeat(5000) + "THE-END";
        RunSummary summary =
                RunSummary.classify(result(1, false, false, padded), TestReport.empty());
        assertThat(summary.outputTail()).hasSize(4000).endsWith("THE-END");
    }

    @Test
    void aBuildPlanNeedsATestCommand() {
        assertThatThrownBy(
                        () ->
                                new BuildPlan(
                                        "maven",
                                        "img",
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        Map.of(),
                                        Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
