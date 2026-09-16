package dev.rook.eval;

import dev.rook.agent.strategy.CodemodOnlyStrategy;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.fixture.FixtureStore;
import dev.rook.eval.generate.FixtureGenerator;
import dev.rook.eval.report.EvalReport;
import dev.rook.eval.report.Reports;
import dev.rook.eval.run.EvalRunner;
import dev.rook.eval.run.FixtureResult;
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
            out.println("usage: generate <directory> | run <fixtures> <results> [filter]");
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
}
