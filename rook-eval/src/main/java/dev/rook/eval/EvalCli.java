package dev.rook.eval;

import dev.rook.agent.strategy.CodemodOnlyStrategy;
import dev.rook.agent.strategy.Strategy;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.fixture.FixtureStore;
import dev.rook.eval.generate.FixtureGenerator;
import dev.rook.eval.report.EvalReport;
import dev.rook.eval.report.Reports;
import dev.rook.eval.run.EvalRunner;
import dev.rook.eval.run.FixtureResult;
import dev.rook.eval.run.ModelStrategies;
import dev.rook.eval.run.PartialResults;
import dev.rook.eval.verify.FixtureSandbox;
import dev.rook.eval.verify.LocalMavenVerifier;
import dev.rook.verify.sandbox.LocalProcessRunner;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class EvalCli {

    private EvalCli() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
        System.exit(run(args, out));
    }

    public static int run(String[] args, PrintStream out) {
        if (args.length < 2) {
            out.println(
                    "usage: generate <directory> | run <fixtures> <results> [filter] | run-model"
                            + " <fixtures> <results> <filter> <base url> <model> <strategies> [gap millis] [iterations]"
                            + " (api key from MODEL_API_KEY)");
            return 2;
        }
        try {
            return switch (args[0]) {
                case "generate" -> generate(Path.of(args[1]), out);
                case "run" ->
                        run(
                                Path.of(args[1]),
                                Path.of(args.length > 2 ? args[2] : "eval/results"),
                                args.length > 3 ? args[3] : "",
                                out);
                case "run-model" ->
                        runModel(
                                Path.of(args[1]),
                                Path.of(args.length > 2 ? args[2] : "eval/results"),
                                args.length > 3 ? args[3] : "",
                                args.length > 4 ? args[4] : "http://localhost:11434/v1",
                                args.length > 5 ? args[5] : "qwen2.5-coder:7b",
                                args.length > 6 ? args[6] : "llm-only,agent",
                                args.length > 7 ? Long.parseLong(args[7]) : 0L,
                                args.length > 8 ? Integer.parseInt(args[8]) : 16,
                                out);
                default -> {
                    out.println("unknown command " + args[0]);
                    yield 2;
                }
            };
        } catch (RuntimeException e) {
            out.println("failed: " + e.getMessage());
            return 1;
        }
    }

    private static int generate(Path directory, PrintStream out) {
        List<Fixture> fixtures = FixtureGenerator.generate(directory);
        out.println("generated " + fixtures.size() + " fixtures in " + directory);
        return 0;
    }

    private static int run(Path fixtures, Path results, String filter, PrintStream out) {
        try {
            Path scratch = Files.createTempDirectory("rook-eval");
            List<Fixture> selected =
                    FixtureStore.loadAll(fixtures).stream()
                            .filter(f -> f.id().contains(filter))
                            .toList();
            LocalMavenVerifier verifier =
                    new LocalMavenVerifier(
                            LocalProcessRunner.create(true),
                            scratch.resolve("verify"),
                            Duration.ofMinutes(4));
            EvalRunner runner =
                    new EvalRunner(
                            verifier,
                            scratch.resolve("work"),
                            Confidence.HEURISTIC,
                            WorkspacePolicy.standard());
            CodemodOnlyStrategy strategy = new CodemodOnlyStrategy();
            List<FixtureResult> rows =
                    selected.parallelStream()
                            .map(fixture -> runner.run(fixture, strategy))
                            .sorted(Comparator.comparing(FixtureResult::fixtureId))
                            .collect(Collectors.toList());
            Map<String, List<FixtureResult>> raw = Map.of(strategy.name(), rows);
            EvalReport report =
                    EvalReport.build(
                            verifier.name(),
                            Confidence.HEURISTIC.name(),
                            raw,
                            List.of("llm-only", "agent"));
            Reports.writeAll(results, report, raw);
            out.print(Reports.text(report));
            return 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int runModel(
            Path fixtures,
            Path results,
            String filter,
            String baseUrl,
            String model,
            String strategies,
            long gapMillis,
            int iterations,
            PrintStream out) {
        try {
            Path scratch = Files.createTempDirectory("rook-eval");
            List<Fixture> selected =
                    FixtureStore.loadAll(fixtures).stream()
                            .filter(f -> f.id().contains(filter))
                            .toList();
            LocalProcessRunner runner = LocalProcessRunner.create(true);
            Duration timeout = Duration.ofMinutes(4);
            LocalMavenVerifier verifier =
                    new LocalMavenVerifier(runner, scratch.resolve("verify"), timeout);
            EvalRunner evaluation =
                    new EvalRunner(
                            verifier,
                            scratch.resolve("work"),
                            Confidence.HEURISTIC,
                            WorkspacePolicy.standard());
            Map<String, List<FixtureResult>> raw = new java.util.TreeMap<>();
            CodemodOnlyStrategy codemods = new CodemodOnlyStrategy();
            raw.put(
                    codemods.name(),
                    selected.parallelStream()
                            .map(fixture -> evaluation.run(fixture, codemods))
                            .sorted(Comparator.comparing(FixtureResult::fixtureId))
                            .collect(Collectors.toList()));
            String key = System.getenv("MODEL_API_KEY");
            ModelStrategies models =
                    new ModelStrategies(
                            baseUrl,
                            key == null || key.isBlank() ? "local" : key,
                            model,
                            Duration.ofMinutes(5),
                            Duration.ofMillis(gapMillis),
                            iterations,
                            results.resolve("traces"));
            PartialResults partial = new PartialResults(results.resolve("partial"));
            List<String> wanted = List.of(strategies.split(","));
            for (String name : wanted) {
                List<FixtureResult> rows = new java.util.ArrayList<>();
                for (Fixture fixture : selected) {
                    Strategy strategy =
                            name.equals("agent")
                                    ? models.agent(
                                            new FixtureSandbox(
                                                    fixture,
                                                    runner,
                                                    scratch.resolve("agent"),
                                                    timeout))
                                    : models.llmOnly();
                    FixtureResult row = partial.load(name, fixture.id()).orElse(null);
                    if (row != null) {
                        out.println(name + " " + fixture.id() + " resumed");
                        rows.add(row);
                        continue;
                    }
                    row = evaluation.run(fixture, strategy);
                    long[] usage = models.takeUsage();
                    String failure = models.takeFailure();
                    if (failure != null) {
                        out.println(
                                "stopped at "
                                        + name
                                        + " "
                                        + fixture.id()
                                        + ": "
                                        + failure
                                        + ". Finished fixtures are kept, run again to continue.");
                        return 3;
                    }
                    partial.save(row);
                    rows.add(row);
                    out.println(
                            name
                                    + " "
                                    + fixture.id()
                                    + " accepted="
                                    + row.accepted()
                                    + " patch="
                                    + row.patchProduced()
                                    + " ms="
                                    + row.latencyMillis()
                                    + " calls="
                                    + usage[0]
                                    + " in="
                                    + usage[1]
                                    + " out="
                                    + usage[2]);
                }
                raw.put(name, rows);
            }
            List<String> notRun = new java.util.ArrayList<>();
            for (String name : List.of("llm-only", "agent")) {
                if (!wanted.contains(name)) {
                    notRun.add(name);
                }
            }
            EvalReport report =
                    EvalReport.build(verifier.name(), Confidence.HEURISTIC.name(), raw, notRun);
            Reports.writeAll(results, report, raw);
            partial.clear();
            out.print(Reports.text(report));
            return 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
