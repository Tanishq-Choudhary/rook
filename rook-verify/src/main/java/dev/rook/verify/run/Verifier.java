package dev.rook.verify.run;

import dev.rook.core.error.RookException;
import dev.rook.verify.build.BuildPlan;
import dev.rook.verify.build.BuildPlanner;
import dev.rook.verify.build.RunSummary;
import dev.rook.verify.build.TestReport;
import dev.rook.verify.compare.Comparison;
import dev.rook.verify.compare.FlakeAnalyzer;
import dev.rook.verify.gate.GateInput;
import dev.rook.verify.gate.GateReport;
import dev.rook.verify.gate.GateResult;
import dev.rook.verify.gate.Gates;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Verifier {

    public static final String BASELINE_RAN = "baseline-ran";

    private final PhaseRunner phases;
    private final Gates gates;

    public Verifier(PhaseRunner phases, Gates gates) {
        this.phases = phases;
        this.gates = gates;
    }

    public VerificationOutcome verify(VerificationRequest request) {
        long started = System.nanoTime();
        BuildPlanner.Result planned;
        try {
            planned =
                    BuildPlanner.detect(
                            request.patchedSource(), request.config().verify(), request.image());
        } catch (RookException e) {
            return VerificationOutcome.unsupported(e.getMessage(), elapsed(started));
        }
        if (planned instanceof BuildPlanner.Unsupported unsupported) {
            return VerificationOutcome.unsupported(unsupported.reason(), elapsed(started));
        }
        BuildPlan plan = ((BuildPlanner.Planned) planned).plan();
        Path cache = request.cacheDir();
        boolean warmed = !PhaseRunner.isWarm(cache);
        RunSummary baseline =
                warmed
                        ? phases.warm(plan, request.baselineSource(), cache)
                        : phases.test(plan, request.baselineSource(), cache, null);
        RunSummary patched = phases.test(plan, request.patchedSource(), cache, null);
        Comparison comparison = Comparison.compare(baseline.report(), patched.report());
        FlakeAnalyzer.Verdict flakes = null;
        if (!comparison.regressions().isEmpty() && request.flakeReruns() > 0) {
            flakes =
                    new FlakeAnalyzer(ids -> rerun(plan, request, ids), request.flakeReruns())
                            .analyze(comparison.regressions());
        }
        GateReport report =
                gates.evaluate(
                        new GateInput(
                                request.diff(),
                                patched,
                                comparison,
                                flakes,
                                request.config().limits().maxFilesChanged(),
                                request.config().limits().maxLinesChanged(),
                                request.config().paths().include(),
                                request.config().paths().exclude(),
                                request.allowBuildFiles()));
        report = withBaseline(report, baseline);
        return new VerificationOutcome(
                report.passed(),
                null,
                report,
                baseline,
                patched,
                comparison,
                flakes,
                warmed,
                elapsed(started));
    }

    private TestReport rerun(BuildPlan plan, VerificationRequest request, Set<String> ids) {
        return phases.test(plan, request.patchedSource(), request.cacheDir(), filter(ids)).report();
    }

    static String filter(Set<String> ids) {
        Set<String> parts = new LinkedHashSet<>();
        for (String id : ids) {
            int hash = id.indexOf('#');
            String className = hash < 0 ? id : id.substring(0, hash);
            String method = hash < 0 ? "" : id.substring(hash + 1);
            int cut = method.length();
            for (char marker : new char[] {'(', '['}) {
                int at = method.indexOf(marker);
                if (at >= 0) {
                    cut = Math.min(cut, at);
                }
            }
            method = method.substring(0, cut);
            parts.add(method.isEmpty() ? className : className + "#" + method);
        }
        return String.join(",", parts);
    }

    private static GateReport withBaseline(GateReport report, RunSummary baseline) {
        List<GateResult> results = new ArrayList<>(report.results());
        if (baseline.ranTests()) {
            results.add(GateResult.pass(BASELINE_RAN, baseline.report().executed() + " tests"));
        } else {
            results.add(
                    GateResult.fail(
                            BASELINE_RAN,
                            "the baseline did not run tests ("
                                    + baseline.status()
                                    + "), new failures cannot be told from old ones"));
        }
        return new GateReport(results);
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
