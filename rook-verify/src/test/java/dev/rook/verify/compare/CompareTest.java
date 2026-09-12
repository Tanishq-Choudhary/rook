package dev.rook.verify.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.verify.build.TestCaseResult;
import dev.rook.verify.build.TestCaseResult.Outcome;
import dev.rook.verify.build.TestReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class CompareTest {

    private static TestReport report(Object... pairs) {
        List<TestCaseResult> results = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            results.add(new TestCaseResult((String) pairs[i], (Outcome) pairs[i + 1], "", 0));
        }
        return TestReport.of(results);
    }

    @Test
    void aTestThatStartedFailingIsARegression() {
        Comparison comparison =
                Comparison.compare(
                        report("a#t", Outcome.PASSED, "a#u", Outcome.PASSED),
                        report("a#t", Outcome.FAILED, "a#u", Outcome.PASSED));
        assertThat(comparison.regressions()).containsExactly("a#t");
        assertThat(comparison.clean()).isFalse();
    }

    @Test
    void errorsCountLikeFailures() {
        Comparison comparison =
                Comparison.compare(report("a#t", Outcome.PASSED), report("a#t", Outcome.ERROR));
        assertThat(comparison.regressions()).containsExactly("a#t");
    }

    @Test
    void failuresPresentBeforeThePatchAreNotBlamedOnIt() {
        Comparison comparison =
                Comparison.compare(
                        report("a#t", Outcome.FAILED, "a#u", Outcome.ERROR),
                        report("a#t", Outcome.ERROR, "a#u", Outcome.FAILED));
        assertThat(comparison.preExisting()).containsExactly("a#t", "a#u");
        assertThat(comparison.regressions()).isEmpty();
        assertThat(comparison.clean()).isTrue();
    }

    @Test
    void testsThePatchFixedAreListed() {
        Comparison comparison =
                Comparison.compare(report("a#t", Outcome.FAILED), report("a#t", Outcome.PASSED));
        assertThat(comparison.fixed()).containsExactly("a#t");
        assertThat(comparison.regressions()).isEmpty();
    }

    @Test
    void newTestsAreAddedAndNewBrokenTestsAreRegressions() {
        Comparison comparison =
                Comparison.compare(
                        report("a#t", Outcome.PASSED),
                        report(
                                "a#t",
                                Outcome.PASSED,
                                "a#new",
                                Outcome.PASSED,
                                "a#bad",
                                Outcome.FAILED));
        assertThat(comparison.added()).containsExactly("a#bad", "a#new");
        assertThat(comparison.regressions()).containsExactly("a#bad");
    }

    @Test
    void deletedTestsAreFlaggedButDeletedSkippedOnesAreNot() {
        Comparison comparison =
                Comparison.compare(
                        report(
                                "a#t",
                                Outcome.PASSED,
                                "a#s",
                                Outcome.SKIPPED,
                                "a#f",
                                Outcome.FAILED),
                        report());
        assertThat(comparison.removed()).containsExactly("a#f", "a#t");
        assertThat(comparison.clean()).isFalse();
    }

    @Test
    void aPassingTestTurnedIntoASkippedOneIsFlagged() {
        Comparison comparison =
                Comparison.compare(report("a#t", Outcome.PASSED), report("a#t", Outcome.SKIPPED));
        assertThat(comparison.newlySkipped()).containsExactly("a#t");
        assertThat(comparison.clean()).isFalse();
        assertThat(
                        Comparison.compare(
                                        report("a#t", Outcome.SKIPPED),
                                        report("a#t", Outcome.SKIPPED))
                                .clean())
                .isTrue();
    }

    @Test
    void identicalReportsAreClean() {
        TestReport same =
                report("a#t", Outcome.PASSED, "a#u", Outcome.FAILED, "a#s", Outcome.SKIPPED);
        Comparison comparison = Comparison.compare(same, same);
        assertThat(comparison.clean()).isTrue();
        assertThat(comparison.preExisting()).containsExactly("a#u");
    }

    @Provide
    Arbitrary<TestReport> reports() {
        Arbitrary<String> ids = Arbitraries.of("a#1", "a#2", "a#3", "b#1", "b#2", "c#1");
        Arbitrary<Outcome> outcomes = Arbitraries.of(Outcome.values());
        return Arbitraries.maps(ids, outcomes)
                .ofMaxSize(6)
                .map(
                        map -> {
                            List<TestCaseResult> list = new ArrayList<>();
                            map.forEach(
                                    (id, outcome) ->
                                            list.add(new TestCaseResult(id, outcome, "", 0)));
                            return TestReport.of(list);
                        });
    }

    @Property
    void aReportComparedWithItselfNeverRegresses(@ForAll("reports") TestReport report) {
        Comparison comparison = Comparison.compare(report, report);
        assertThat(comparison.regressions()).isEmpty();
        assertThat(comparison.added()).isEmpty();
        assertThat(comparison.removed()).isEmpty();
        assertThat(comparison.fixed()).isEmpty();
    }

    @Property
    void everyListIsDrawnFromTheRightSide(
            @ForAll("reports") TestReport baseline, @ForAll("reports") TestReport patched) {
        Comparison comparison = Comparison.compare(baseline, patched);
        Set<String> before = baseline.cases().keySet();
        Set<String> after = patched.cases().keySet();
        assertThat(comparison.regressions()).allMatch(after::contains);
        assertThat(comparison.fixed()).allMatch(id -> before.contains(id) && after.contains(id));
        assertThat(comparison.removed()).allMatch(id -> before.contains(id) && !after.contains(id));
        assertThat(comparison.added()).allMatch(id -> !before.contains(id) && after.contains(id));
        assertThat(Collections.disjoint(comparison.regressions(), comparison.preExisting()))
                .isTrue();
        assertThat(Collections.disjoint(comparison.regressions(), comparison.fixed())).isTrue();
    }

    @Property
    void aTestFailingOnlyAfterThePatchIsAlwaysARegression(
            @ForAll("reports") TestReport baseline, @ForAll("reports") TestReport patched) {
        Comparison comparison = Comparison.compare(baseline, patched);
        for (Map.Entry<String, TestCaseResult> entry : patched.cases().entrySet()) {
            TestCaseResult old = baseline.cases().get(entry.getKey());
            if (entry.getValue().broken() && (old == null || !old.broken())) {
                assertThat(comparison.regressions()).contains(entry.getKey());
            }
        }
    }

    private static final class ScriptedRerunner implements FlakeAnalyzer.Rerunner {

        private final List<Map<String, Outcome>> script;
        final List<Set<String>> asked = new ArrayList<>();
        private int next;

        ScriptedRerunner(List<Map<String, Outcome>> script) {
            this.script = script;
        }

        @Override
        public TestReport rerun(Set<String> testIds) {
            asked.add(testIds);
            Map<String, Outcome> outcomes = next < script.size() ? script.get(next) : Map.of();
            next++;
            List<TestCaseResult> results = new ArrayList<>();
            for (String id : testIds) {
                Outcome outcome = outcomes.get(id);
                if (outcome != null) {
                    results.add(new TestCaseResult(id, outcome, "", 0));
                }
            }
            return TestReport.of(results);
        }
    }

    @Test
    void aFailureThatPassesOnARerunIsFlaky() {
        ScriptedRerunner rerunner =
                new ScriptedRerunner(List.of(Map.of("a#t", Outcome.PASSED, "a#u", Outcome.FAILED)));
        FlakeAnalyzer.Verdict verdict =
                new FlakeAnalyzer(rerunner, 1).analyze(List.of("a#t", "a#u"));
        assertThat(verdict.flaky()).containsExactly("a#t");
        assertThat(verdict.confirmed()).containsExactly("a#u");
        assertThat(verdict.rerunsPerformed()).isEqualTo(1);
    }

    @Test
    void onlyTheStillFailingTestsAreRerunAgain() {
        ScriptedRerunner rerunner =
                new ScriptedRerunner(
                        List.of(
                                Map.of("a#t", Outcome.FAILED, "a#u", Outcome.FAILED),
                                Map.of("a#t", Outcome.PASSED, "a#u", Outcome.FAILED),
                                Map.of("a#u", Outcome.FAILED)));
        FlakeAnalyzer.Verdict verdict =
                new FlakeAnalyzer(rerunner, 3).analyze(List.of("a#t", "a#u"));
        assertThat(verdict.flaky()).containsExactly("a#t");
        assertThat(verdict.confirmed()).containsExactly("a#u");
        assertThat(rerunner.asked).hasSize(3);
        assertThat(rerunner.asked.get(1)).containsExactlyInAnyOrder("a#t", "a#u");
        assertThat(rerunner.asked.get(2)).containsExactly("a#u");
    }

    @Test
    void rerunsStopOnceNothingIsLeftToJudge() {
        ScriptedRerunner rerunner = new ScriptedRerunner(List.of(Map.of("a#t", Outcome.PASSED)));
        FlakeAnalyzer.Verdict verdict = new FlakeAnalyzer(rerunner, 5).analyze(List.of("a#t"));
        assertThat(verdict.rerunsPerformed()).isEqualTo(1);
        assertThat(verdict.confirmed()).isEmpty();
    }

    @Test
    void aTestThatDoesNotShowUpInTheRerunStaysConfirmed() {
        ScriptedRerunner rerunner = new ScriptedRerunner(List.of(Map.of()));
        FlakeAnalyzer.Verdict verdict = new FlakeAnalyzer(rerunner, 1).analyze(List.of("a#t"));
        assertThat(verdict.confirmed()).containsExactly("a#t");
    }

    @Test
    void noSuspectsMeansNoReruns() {
        ScriptedRerunner rerunner = new ScriptedRerunner(List.of());
        assertThat(new FlakeAnalyzer(rerunner, 3).analyze(List.of()).rerunsPerformed()).isZero();
        assertThat(rerunner.asked).isEmpty();
    }

    @Test
    void atLeastOneAttemptIsRequired() {
        assertThatThrownBy(() -> new FlakeAnalyzer(ids -> TestReport.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
