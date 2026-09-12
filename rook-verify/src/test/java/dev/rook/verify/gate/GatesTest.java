package dev.rook.verify.gate;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.patch.UnifiedDiff;
import dev.rook.verify.build.RunSummary;
import dev.rook.verify.build.TestCaseResult;
import dev.rook.verify.build.TestCaseResult.Outcome;
import dev.rook.verify.build.TestReport;
import dev.rook.verify.compare.Comparison;
import dev.rook.verify.compare.FlakeAnalyzer;
import dev.rook.verify.sandbox.SandboxResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatesTest {

    private static final String SERVICE_BEFORE =
            "class Service {\n    void go() {\n        client.charge(5);\n    }\n}\n";
    private static final String SERVICE_AFTER =
            "class Service {\n    void go() {\n        client.createCharge(5);\n    }\n}\n";
    private static final String SERVICE = "src/main/java/com/shop/Service.java";

    private final Gates gates = new Gates();

    private static String goodDiff() {
        return UnifiedDiff.generate(SERVICE, SERVICE_BEFORE, SERVICE_AFTER);
    }

    private static TestReport report(Object... pairs) {
        List<TestCaseResult> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(new TestCaseResult((String) pairs[i], (Outcome) pairs[i + 1], "", 0));
        }
        return TestReport.of(list);
    }

    private static RunSummary ran(TestReport report) {
        return RunSummary.classify(
                new SandboxResult(0, false, false, false, "", "", Duration.ZERO), report);
    }

    private static GateInput input(String diff, RunSummary patched, Comparison comparison) {
        return new GateInput(diff, patched, comparison, null, 10, 100, List.of(), List.of(), false);
    }

    private static GateInput healthy(String diff) {
        TestReport same = report("a#t", Outcome.PASSED, "a#u", Outcome.PASSED);
        return input(diff, ran(same), Comparison.compare(same, same));
    }

    private static GateResult result(GateReport report, String name) {
        return report.results().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aHealthyPatchPassesEveryGate() {
        GateReport report = gates.evaluate(healthy(goodDiff()));
        assertThat(report.passed()).as(report.summary()).isTrue();
        assertThat(report.results()).hasSize(11);
        assertThat(report.summary()).isEqualTo("all 11 gates passed");
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void anEmptyPatchIsRejected() {
        GateReport report = gates.evaluate(healthy(""));
        assertThat(result(report, Gates.HAS_CHANGES).passed()).isFalse();
        assertThat(report.passed()).isFalse();
    }

    @Test
    void aMalformedPatchRejectsEverythingWithOneReason() {
        GateReport report = gates.evaluate(healthy("this is not a diff"));
        assertThat(report.passed()).isFalse();
        assertThat(report.results())
                .singleElement()
                .extracting(GateResult::name)
                .isEqualTo("diff-readable");
    }

    @Test
    void missingResultsFailTheGatesThatNeedThem() {
        GateReport report = gates.evaluate(input(goodDiff(), null, null));
        assertThat(result(report, Gates.COMPILES).passed()).isFalse();
        assertThat(result(report, Gates.TESTS_EXECUTED).passed()).isFalse();
        assertThat(result(report, Gates.NO_NEW_FAILURES).detail()).contains("baseline");
        assertThat(result(report, Gates.TESTS_PRESERVED).passed()).isFalse();
        assertThat(report.passed()).isFalse();
    }

    @Test
    void aBuildThatDoesNotCompileFails() {
        for (int exit : new int[] {1}) {
            SandboxResult broken =
                    new SandboxResult(
                            exit, false, false, false, "COMPILATION ERROR", "", Duration.ZERO);
            GateInput in =
                    input(
                            goodDiff(),
                            RunSummary.classify(broken, TestReport.empty()),
                            Comparison.compare(report(), report()));
            assertThat(result(gates.evaluate(in), Gates.COMPILES).detail())
                    .contains("does not compile");
        }
        SandboxResult timeout = new SandboxResult(124, true, false, false, "", "", Duration.ZERO);
        GateInput in =
                input(
                        goodDiff(),
                        RunSummary.classify(timeout, TestReport.empty()),
                        Comparison.compare(report(), report()));
        assertThat(result(gates.evaluate(in), Gates.COMPILES).detail()).contains("timed out");
    }

    @Test
    void aRunWithoutTestsCannotBeVerified() {
        GateInput in =
                input(goodDiff(), ran(TestReport.empty()), Comparison.compare(report(), report()));
        assertThat(result(gates.evaluate(in), Gates.TESTS_EXECUTED).passed()).isFalse();
    }

    @Test
    void newFailuresFailTheGateUnlessTheyAreFlaky() {
        TestReport before = report("a#t", Outcome.PASSED);
        TestReport after = report("a#t", Outcome.FAILED);
        Comparison comparison = Comparison.compare(before, after);
        GateInput plain = input(goodDiff(), ran(after), comparison);
        assertThat(result(gates.evaluate(plain), Gates.NO_NEW_FAILURES).detail()).contains("a#t");
        FlakeAnalyzer.Verdict flaky = new FlakeAnalyzer.Verdict(List.of(), List.of("a#t"), 1);
        GateInput withFlake =
                new GateInput(
                        goodDiff(),
                        ran(after),
                        comparison,
                        flaky,
                        10,
                        100,
                        List.of(),
                        List.of(),
                        false);
        GateResult tolerated = result(gates.evaluate(withFlake), Gates.NO_NEW_FAILURES);
        assertThat(tolerated.passed()).isTrue();
        assertThat(tolerated.detail()).contains("1 flaky");
        FlakeAnalyzer.Verdict confirmed = new FlakeAnalyzer.Verdict(List.of("a#t"), List.of(), 2);
        GateInput stillBroken =
                new GateInput(
                        goodDiff(),
                        ran(after),
                        comparison,
                        confirmed,
                        10,
                        100,
                        List.of(),
                        List.of(),
                        false);
        assertThat(result(gates.evaluate(stillBroken), Gates.NO_NEW_FAILURES).passed()).isFalse();
    }

    @Test
    void deletedOrSkippedTestsFailThePreservationGate() {
        TestReport before = report("a#t", Outcome.PASSED, "a#u", Outcome.PASSED);
        GateInput removed =
                input(
                        goodDiff(),
                        ran(report("a#t", Outcome.PASSED)),
                        Comparison.compare(before, report("a#t", Outcome.PASSED)));
        assertThat(result(gates.evaluate(removed), Gates.TESTS_PRESERVED).detail())
                .contains("disappeared")
                .contains("a#u");
        GateInput skipped =
                input(
                        goodDiff(),
                        ran(report("a#t", Outcome.PASSED, "a#u", Outcome.SKIPPED)),
                        Comparison.compare(
                                before, report("a#t", Outcome.PASSED, "a#u", Outcome.SKIPPED)));
        assertThat(result(gates.evaluate(skipped), Gates.TESTS_PRESERVED).detail())
                .contains("skipped");
    }

    @Test
    void weakeningTestsFailsTheGate() {
        String testBefore =
                "class T {\n    @Test void a() {\n        assertThat(x).isEqualTo(1);\n        assertThat(y).isEqualTo(2);\n    }\n}\n";
        String testAfter =
                "class T {\n    @Test void a() {\n        assertThat(x).isEqualTo(1);\n    }\n}\n";
        String diff =
                goodDiff() + UnifiedDiff.generate("src/test/java/T.java", testBefore, testAfter);
        GateResult result = result(gates.evaluate(healthy(diff)), Gates.TESTS_NOT_WEAKENED);
        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("1 assertions were removed");
        String deleted =
                goodDiff() + UnifiedDiff.generate("src/test/java/T.java", testBefore, null);
        assertThat(result(gates.evaluate(healthy(deleted)), Gates.TESTS_NOT_WEAKENED).detail())
                .contains("deleted");
    }

    @Test
    void updatingTestsToTheNewApiIsFine() {
        String testBefore =
                "class T {\n    @Test void a() {\n        assertThat(client.charge(1)).isNotNull();\n    }\n}\n";
        String testAfter =
                "class T {\n    @Test void a() {\n        assertThat(client.createCharge(1)).isNotNull();\n    }\n}\n";
        String diff =
                goodDiff() + UnifiedDiff.generate("src/test/java/T.java", testBefore, testAfter);
        assertThat(result(gates.evaluate(healthy(diff)), Gates.TESTS_NOT_WEAKENED).passed())
                .isTrue();
    }

    @Test
    void sizeLimitsAreEnforced() {
        GateInput tight =
                new GateInput(
                        goodDiff(),
                        healthy(goodDiff()).patched(),
                        healthy(goodDiff()).comparison(),
                        null,
                        1,
                        1,
                        List.of(),
                        List.of(),
                        false);
        assertThat(result(gates.evaluate(tight), Gates.WITHIN_LIMITS).detail())
                .contains("lines changed");
        String two = goodDiff() + UnifiedDiff.generate("src/main/java/B.java", "a\n", "b\n");
        GateInput files =
                new GateInput(
                        two,
                        healthy(two).patched(),
                        healthy(two).comparison(),
                        null,
                        1,
                        100,
                        List.of(),
                        List.of(),
                        false);
        assertThat(result(gates.evaluate(files), Gates.WITHIN_LIMITS).detail())
                .contains("2 files changed");
    }

    @Test
    void includeAndExcludeGlobsAndSensitivePathsAreEnforced() {
        String other = UnifiedDiff.generate("legacy/old/Thing.java", "a\n", "b\n");
        GateInput in =
                new GateInput(
                        goodDiff() + other,
                        healthy(goodDiff()).patched(),
                        healthy(goodDiff()).comparison(),
                        null,
                        10,
                        100,
                        List.of("src/**"),
                        List.of(),
                        false);
        assertThat(result(gates.evaluate(in), Gates.ALLOWED_PATHS).detail())
                .contains("legacy/old/Thing.java");
        GateInput excluded =
                new GateInput(
                        goodDiff(),
                        healthy(goodDiff()).patched(),
                        healthy(goodDiff()).comparison(),
                        null,
                        10,
                        100,
                        List.of(),
                        List.of("src/main/java/com/shop/"),
                        false);
        assertThat(result(gates.evaluate(excluded), Gates.ALLOWED_PATHS).passed()).isFalse();
        String secrets = UnifiedDiff.generate("config/credentials.json", "{}\n", "{\"a\":1}\n");
        assertThat(result(gates.evaluate(healthy(secrets)), Gates.ALLOWED_PATHS).passed())
                .isFalse();
        assertThat(result(gates.evaluate(healthy(goodDiff())), Gates.ALLOWED_PATHS).passed())
                .isTrue();
    }

    @Test
    void buildFilesWorkflowsAndRookConfigAreProtected() {
        for (String path :
                List.of(
                        "pom.xml",
                        ".github/workflows/ci.yml",
                        ".rook.yml",
                        "module/build.gradle")) {
            String diff = goodDiff() + UnifiedDiff.generate(path, "a\n", "b\n");
            GateResult result = result(gates.evaluate(healthy(diff)), Gates.NO_PROTECTED_FILES);
            assertThat(result.passed()).as(path).isFalse();
        }
        String pom = goodDiff() + UnifiedDiff.generate("pom.xml", "a\n", "b\n");
        GateInput allowed =
                new GateInput(
                        pom,
                        healthy(pom).patched(),
                        healthy(pom).comparison(),
                        null,
                        10,
                        100,
                        List.of(),
                        List.of(),
                        true);
        assertThat(result(gates.evaluate(allowed), Gates.NO_PROTECTED_FILES).passed()).isTrue();
        String workflow =
                goodDiff() + UnifiedDiff.generate(".github/workflows/ci.yml", "a\n", "b\n");
        GateInput stillProtected =
                new GateInput(
                        workflow,
                        healthy(workflow).patched(),
                        healthy(workflow).comparison(),
                        null,
                        10,
                        100,
                        List.of(),
                        List.of(),
                        true);
        assertThat(result(gates.evaluate(stillProtected), Gates.NO_PROTECTED_FILES).passed())
                .isFalse();
    }

    @Test
    void addedDependenciesAreCaughtEvenWhenBuildFilesAreAllowed() {
        String before = "<project>\n  <dependencies>\n  </dependencies>\n</project>\n";
        String after =
                "<project>\n  <dependencies>\n    <dependency>\n      <artifactId>evil</artifactId>\n    </dependency>\n  </dependencies>\n</project>\n";
        String pom = goodDiff() + UnifiedDiff.generate("pom.xml", before, after);
        GateInput allowed =
                new GateInput(
                        pom,
                        healthy(pom).patched(),
                        healthy(pom).comparison(),
                        null,
                        10,
                        100,
                        List.of(),
                        List.of(),
                        true);
        GateResult result = result(gates.evaluate(allowed), Gates.NO_NEW_DEPENDENCIES);
        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("pom.xml");
        String gradle =
                goodDiff()
                        + UnifiedDiff.generate(
                                "build.gradle",
                                "plugins {}\n",
                                "plugins {}\nimplementation 'x:y:1'\n");
        assertThat(result(gates.evaluate(healthy(gradle)), Gates.NO_NEW_DEPENDENCIES).passed())
                .isFalse();
        assertThat(result(gates.evaluate(healthy(goodDiff())), Gates.NO_NEW_DEPENDENCIES).passed())
                .isTrue();
    }

    @Test
    void secretsInAddedLinesFailTheGateButRemovedOnesDoNot() {
        String added =
                UnifiedDiff.generate(
                        SERVICE,
                        SERVICE_BEFORE,
                        SERVICE_BEFORE.replace(
                                "charge(5)", "charge(\"ghp_" + "a".repeat(36) + "\")"));
        GateResult bad = result(gates.evaluate(healthy(added)), Gates.NO_SECRETS);
        assertThat(bad.passed()).isFalse();
        assertThat(bad.detail()).contains("github-token");
        String removed =
                UnifiedDiff.generate(
                        SERVICE,
                        SERVICE_BEFORE.replace(
                                "charge(5)", "charge(\"ghp_" + "a".repeat(36) + "\")"),
                        SERVICE_BEFORE);
        assertThat(result(gates.evaluate(healthy(removed)), Gates.NO_SECRETS).passed()).isTrue();
    }

    @Test
    void aReportListsTheFailuresInItsSummary() {
        String diff = goodDiff() + UnifiedDiff.generate("pom.xml", "a\n", "b\n");
        GateReport report = gates.evaluate(healthy(diff));
        assertThat(report.passed()).isFalse();
        assertThat(report.summary()).contains("no-protected-files");
        assertThat(new GateReport(List.of()).passed()).isFalse();
        assertThat(new GateReport(List.of()).summary()).isEqualTo("no gates ran");
        assertThat(GateResult.pass("x", "d").detail()).isEqualTo("d");
    }
}
