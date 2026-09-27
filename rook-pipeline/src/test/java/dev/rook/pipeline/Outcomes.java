package dev.rook.pipeline;

import dev.rook.verify.build.RunSummary;
import dev.rook.verify.build.TestCaseResult;
import dev.rook.verify.build.TestReport;
import dev.rook.verify.compare.Comparison;
import dev.rook.verify.compare.FlakeAnalyzer;
import dev.rook.verify.gate.GateReport;
import dev.rook.verify.gate.GateResult;
import dev.rook.verify.run.VerificationOutcome;
import java.time.Duration;
import java.util.List;

public final class Outcomes {

    private Outcomes() {}

    public static VerificationOutcome passing() {
        RunSummary run = tests(false);
        return new VerificationOutcome(
                true,
                null,
                new GateReport(
                        List.of(GateResult.pass("compiles"), GateResult.pass("no-regressions"))),
                run,
                run,
                Comparison.compare(run.report(), run.report()),
                null,
                true,
                Duration.ofSeconds(9));
    }

    public static VerificationOutcome failing() {
        RunSummary baseline = tests(false);
        RunSummary patched = tests(true);
        return new VerificationOutcome(
                false,
                null,
                new GateReport(
                        List.of(
                                GateResult.fail(
                                        "no-regressions", "ServiceTest.go started failing"))),
                baseline,
                patched,
                Comparison.compare(baseline.report(), patched.report()),
                new FlakeAnalyzer.Verdict(List.of("ServiceTest#go"), List.of(), 2),
                false,
                Duration.ofSeconds(4));
    }

    static RunSummary tests(boolean breakOne) {
        return new RunSummary(
                RunSummary.Status.TESTS_RAN,
                TestReport.of(
                        List.of(
                                new TestCaseResult(
                                        "com.shop.ServiceTest#go",
                                        TestCaseResult.Outcome.PASSED,
                                        "",
                                        0.1),
                                new TestCaseResult(
                                        "com.shop.ServiceTest#again",
                                        breakOne
                                                ? TestCaseResult.Outcome.FAILED
                                                : TestCaseResult.Outcome.PASSED,
                                        breakOne ? "expected 1" : "",
                                        0.1))),
                "");
    }
}
