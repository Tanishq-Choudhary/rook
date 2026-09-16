package dev.rook.eval.report;

import dev.rook.core.json.Json;
import dev.rook.eval.run.FixtureResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class Reports {

    private Reports() {}

    public static String json(EvalReport report) {
        return Json.writePretty(report) + "\n";
    }

    public static String text(EvalReport report) {
        StringBuilder out = new StringBuilder();
        out.append(
                String.format(
                        Locale.ROOT,
                        "fixtures %d, verifier %s, impact bar %s%n%n",
                        report.fixtures(),
                        report.verifier(),
                        report.minimumConfidence()));
        out.append(
                String.format(
                        Locale.ROOT,
                        "%-14s %7s %7s %9s %8s %8s %9s %9s%n",
                        "strategy",
                        "success",
                        "escapes",
                        "noopEdit",
                        "protEdit",
                        "cost/ok",
                        "p50 ms",
                        "p95 ms"));
        for (StrategyMetrics m : report.strategies()) {
            out.append(
                    String.format(
                            Locale.ROOT,
                            "%-14s %6.1f%% %7d %9d %8d %8d %9d %9d%n",
                            m.strategy(),
                            m.patchSuccessRate() * 100,
                            m.regressionEscapes(),
                            m.noopFixturesEdited(),
                            m.protectedFilesEdited(),
                            m.costPerAcceptedMicros(),
                            m.latencyP50Millis(),
                            m.latencyP95Millis()));
        }
        for (StrategyMetrics m : report.strategies()) {
            out.append(
                    String.format(
                            Locale.ROOT,
                            "%n%s: impact precision %.3f, recall %.3f, patches produced %d of %d%n",
                            m.strategy(),
                            m.impactPrecision(),
                            m.impactRecall(),
                            m.patchesProduced(),
                            m.needingChange()));
            m.categories()
                    .forEach(
                            (name, c) ->
                                    out.append(
                                            String.format(
                                                    Locale.ROOT,
                                                    "  %-24s %2d of %2d accepted%n",
                                                    name,
                                                    c.accepted(),
                                                    c.total())));
            m.failures()
                    .forEach(
                            (reason, count) ->
                                    out.append(
                                            String.format(
                                                    Locale.ROOT,
                                                    "  failure: %s x%d%n",
                                                    reason,
                                                    count)));
        }
        if (!report.notRun().isEmpty()) {
            out.append("\nnot run: ").append(String.join(", ", report.notRun())).append('\n');
        }
        out.append("\nlimitations\n");
        report.limitations().forEach(line -> out.append("- ").append(line).append('\n'));
        return out.toString();
    }

    public static void writeAll(
            Path directory, EvalReport report, Map<String, List<FixtureResult>> raw) {
        try {
            Files.createDirectories(directory);
            Files.writeString(
                    directory.resolve("report.json"), json(report), StandardCharsets.UTF_8);
            Files.writeString(
                    directory.resolve("report.txt"), text(report), StandardCharsets.UTF_8);
            Map<String, List<FixtureResult>> ordered = new TreeMap<>(raw);
            for (Map.Entry<String, List<FixtureResult>> entry : ordered.entrySet()) {
                List<FixtureResult> sorted =
                        entry.getValue().stream()
                                .sorted(Comparator.comparing(FixtureResult::fixtureId))
                                .toList();
                Files.writeString(
                        directory.resolve("results-" + entry.getKey() + ".json"),
                        Json.writePretty(sorted) + "\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
