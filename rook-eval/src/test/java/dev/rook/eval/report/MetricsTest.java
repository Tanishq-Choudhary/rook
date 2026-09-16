package dev.rook.eval.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.rook.core.json.Json;
import dev.rook.eval.run.FixtureResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsTest {

    private static FixtureResult result(
            String id,
            String category,
            boolean noop,
            boolean produced,
            boolean accepted,
            List<String> falseEdits,
            boolean escape,
            long cost,
            long latency,
            String failure) {
        return new FixtureResult(
                id,
                category,
                "easy",
                noop,
                "codemod-only",
                "NEEDS_MIGRATION",
                2,
                0,
                0,
                produced,
                produced ? List.of("a.java") : List.of(),
                falseEdits,
                accepted,
                accepted,
                escape,
                accepted,
                cost,
                latency,
                "DONE",
                List.of(),
                failure);
    }

    private static List<FixtureResult> sample() {
        return List.of(
                result("a", "rename", false, true, true, List.of(), false, 100, 10, ""),
                result(
                        "b",
                        "rename",
                        false,
                        true,
                        false,
                        List.of(),
                        true,
                        300,
                        30,
                        "independent tests fail"),
                result(
                        "c",
                        "type",
                        false,
                        false,
                        false,
                        List.of(),
                        false,
                        0,
                        20,
                        "no patch produced"),
                result(
                        "d",
                        "trap",
                        false,
                        true,
                        false,
                        List.of("Ledger.java"),
                        false,
                        50,
                        40,
                        "edited files that must stay unchanged"),
                result("e", "quiet", true, false, true, List.of(), false, 0, 50, ""),
                result(
                        "f",
                        "quiet",
                        true,
                        true,
                        false,
                        List.of("Service.java"),
                        false,
                        10,
                        60,
                        "visible tests fail"));
    }

    @Test
    void aggregatesCountsRatesAndFailureReasons() {
        StrategyMetrics m = Metrics.of("codemod-only", sample());
        assertThat(m.fixtures()).isEqualTo(6);
        assertThat(m.needingChange()).isEqualTo(4);
        assertThat(m.noops()).isEqualTo(2);
        assertThat(m.patchesProduced()).isEqualTo(4);
        assertThat(m.accepted()).isEqualTo(2);
        assertThat(m.patchSuccessRate()).isEqualTo(0.25);
        assertThat(m.regressionEscapes()).isEqualTo(1);
        assertThat(m.noopFixturesEdited()).isEqualTo(1);
        assertThat(m.protectedFilesEdited()).isEqualTo(1);
        assertThat(m.totalCostMicros()).isEqualTo(460);
        assertThat(m.costPerAcceptedMicros()).isEqualTo(100);
        assertThat(m.impactPrecision()).isEqualTo(1.0);
        assertThat(m.impactRecall()).isEqualTo(1.0);
        assertThat(m.failures())
                .containsEntry("no patch produced", 1)
                .containsEntry("visible tests fail", 1)
                .hasSize(4);
        assertThat(m.categories().get("rename"))
                .isEqualTo(new StrategyMetrics.CategoryMetrics(2, 2, 1));
        assertThat(m.categories().get("quiet"))
                .isEqualTo(new StrategyMetrics.CategoryMetrics(2, 0, 1));
    }

    @Test
    void latencyPercentilesUseNearestRank() {
        StrategyMetrics m = Metrics.of("codemod-only", sample());
        assertThat(m.latencyP50Millis()).isEqualTo(30);
        assertThat(m.latencyP95Millis()).isEqualTo(60);
        assertThat(Metrics.percentile(List.of(), 50)).isZero();
        assertThat(Metrics.percentile(List.of(7L), 95)).isEqualTo(7);
        assertThat(Metrics.percentile(List.of(5L, 1L, 3L), 50)).isEqualTo(3);
    }

    @Test
    void impactRatesPoolTheCountsAcrossFixtures() {
        FixtureResult noisy =
                new FixtureResult(
                        "n",
                        "rename",
                        "easy",
                        false,
                        "s",
                        "NEEDS_MIGRATION",
                        3,
                        1,
                        2,
                        false,
                        List.of(),
                        List.of(),
                        false,
                        false,
                        false,
                        false,
                        0,
                        0,
                        "DONE",
                        List.of(),
                        "");
        StrategyMetrics m = Metrics.of("s", List.of(noisy));
        assertThat(m.impactPrecision()).isCloseTo(0.75, within(1e-9));
        assertThat(m.impactRecall()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void anEmptyRunHasNeutralRates() {
        StrategyMetrics m = Metrics.of("none", List.of());
        assertThat(m.patchSuccessRate()).isEqualTo(1.0);
        assertThat(m.costPerAcceptedMicros()).isZero();
        assertThat(m.latencyP50Millis()).isZero();
    }

    @Test
    void textReportListsStrategiesCategoriesAndLimitations() {
        EvalReport report =
                EvalReport.build(
                        "local-maven",
                        "HEURISTIC",
                        Map.of("codemod-only", sample(), "agent", sample()),
                        List.of("llm-only"));
        String text = Reports.text(report);
        assertThat(report.strategies())
                .extracting(StrategyMetrics::strategy)
                .containsExactly("agent", "codemod-only");
        assertThat(text)
                .contains("fixtures 6, verifier local-maven")
                .contains("success")
                .contains("25.0%")
                .contains("rename")
                .contains("failure: no patch produced x1")
                .contains("not run: llm-only")
                .contains("limitations")
                .contains("No model backed strategy");
        assertThat(text)
                .doesNotContain(String.valueOf((char) 0x2014))
                .doesNotContain(String.valueOf((char) 0x2013));
    }

    @Test
    void jsonRoundTripsThroughTheReportRecord() {
        EvalReport report =
                EvalReport.build(
                        "local-maven", "HEURISTIC", Map.of("codemod-only", sample()), List.of());
        EvalReport back = Json.read(Reports.json(report), EvalReport.class);
        assertThat(back).isEqualTo(report);
    }

    @Test
    void reportsAndRawResultsAreWrittenInStableOrder(@TempDir Path temp) throws IOException {
        Map<String, List<FixtureResult>> raw = Map.of("codemod-only", sample());
        EvalReport report = EvalReport.build("local-maven", "HEURISTIC", raw, List.of());
        Reports.writeAll(temp.resolve("out"), report, raw);
        Reports.writeAll(temp.resolve("again"), report, raw);
        assertThat(temp.resolve("out/report.json")).exists();
        assertThat(temp.resolve("out/report.txt")).exists();
        String results = Files.readString(temp.resolve("out/results-codemod-only.json"));
        assertThat(results.indexOf("\"a\"")).isLessThan(results.indexOf("\"b\""));
        assertThat(Files.readString(temp.resolve("again/results-codemod-only.json")))
                .isEqualTo(results);
        assertThat(Files.readString(temp.resolve("again/report.json")))
                .isEqualTo(Files.readString(temp.resolve("out/report.json")));
    }
}
