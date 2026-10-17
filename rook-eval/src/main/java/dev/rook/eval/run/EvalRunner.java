package dev.rook.eval.run;

import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.strategy.Strategy;
import dev.rook.agent.strategy.StrategyOutcome;
import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.changes.jar.ApiSurfaceReader;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.JavaIndexer;
import dev.rook.codeintel.project.RepoProfile;
import dev.rook.eval.analysis.FixtureAnalysis;
import dev.rook.eval.analysis.SdkApiNotes;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.verify.FixtureVerifier;
import dev.rook.eval.verify.SdkVersion;
import dev.rook.eval.verify.Verdict;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class EvalRunner {

    private final FixtureVerifier verifier;
    private final Path work;
    private final Confidence minimum;
    private final WorkspacePolicy policy;

    public EvalRunner(
            FixtureVerifier verifier, Path work, Confidence minimum, WorkspacePolicy policy) {
        this.verifier = verifier;
        this.work = work;
        this.minimum = minimum;
        this.policy = policy;
    }

    public FixtureResult run(Fixture fixture, Strategy strategy) {
        Path base = work.resolve(fixture.id()).resolve(strategy.name());
        FixtureAnalysis analysis = FixtureAnalysis.run(fixture, base.resolve("sdk"), minimum);
        StrategyOutcome outcome;
        Workspace workspace = Workspace.copyOf(fixture.repo(), base.resolve("workspace"), policy);
        try {
            outcome = strategy.run(task(fixture, workspace, analysis), workspace);
        } catch (RuntimeException e) {
            return failed(fixture, strategy, analysis, "strategy failed: " + e.getMessage());
        }
        boolean mustChange = !fixture.spec().noop();
        Verdict verdict = null;
        if (outcome.hasPatch() || !mustChange) {
            verdict = verifier.verify(fixture, workspace.root(), SdkVersion.AFTER);
        }
        List<String> falseEdits = falseEdits(fixture, outcome.changedPaths());
        boolean visible = verdict != null && verdict.visiblePassed();
        boolean independent = verdict != null && verdict.independentPassed();
        boolean escape = visible && !independent;
        boolean accepted = visible && independent && falseEdits.isEmpty();
        return new FixtureResult(
                fixture.id(),
                fixture.spec().category(),
                fixture.spec().difficulty(),
                fixture.spec().noop(),
                strategy.name(),
                analysis.analysis().assessment().decision().name(),
                analysis.score().truePositives(),
                analysis.score().falsePositives(),
                analysis.score().falseNegatives(),
                outcome.hasPatch(),
                outcome.changedPaths(),
                falseEdits,
                visible,
                independent,
                escape,
                accepted,
                outcome.costMicros(),
                outcome.elapsedMillis(),
                outcome.status(),
                outcome.unresolved(),
                failure(verdict, falseEdits, mustChange));
    }

    private AgentTask task(Fixture fixture, Workspace workspace, FixtureAnalysis analysis) {
        RepoProfile profile = RepoProfile.detect(workspace.root());
        List<String> roots = new ArrayList<>(profile.mainRoots());
        roots.addAll(profile.testRoots());
        CodeIndex index = JavaIndexer.index(workspace.root(), roots);
        return new AgentTask(
                fixture.id(),
                analysis.change().events(),
                analysis.analysis().report(),
                List.of(),
                index,
                SdkApiNotes.render(
                        analysis.change().events(),
                        ApiSurfaceReader.read(analysis.change().afterJar())));
    }

    private FixtureResult failed(
            Fixture fixture, Strategy strategy, FixtureAnalysis analysis, String reason) {
        RepoAnalyzer.Assessment assessment = analysis.analysis().assessment();
        return new FixtureResult(
                fixture.id(),
                fixture.spec().category(),
                fixture.spec().difficulty(),
                fixture.spec().noop(),
                strategy.name(),
                assessment.decision().name(),
                analysis.score().truePositives(),
                analysis.score().falsePositives(),
                analysis.score().falseNegatives(),
                false,
                List.of(),
                List.of(),
                false,
                false,
                false,
                false,
                0,
                0,
                "ERROR",
                List.of(),
                reason);
    }

    static List<String> falseEdits(Fixture fixture, List<String> changed) {
        Set<String> untouchable = Set.copyOf(fixture.spec().untouchable());
        List<String> edits = new ArrayList<>();
        for (String path : changed) {
            if (fixture.spec().noop() || untouchable.contains(path)) {
                edits.add(path);
            }
        }
        return edits;
    }

    private static String failure(Verdict verdict, List<String> falseEdits, boolean mustChange) {
        if (verdict == null) {
            return mustChange ? "no patch produced" : "";
        }
        if (!verdict.compiled()) {
            return "does not compile";
        }
        if (!verdict.visiblePassed()) {
            return "visible tests fail";
        }
        if (!verdict.independentPassed()) {
            return "independent tests fail";
        }
        return falseEdits.isEmpty() ? "" : "edited files that must stay unchanged";
    }
}
