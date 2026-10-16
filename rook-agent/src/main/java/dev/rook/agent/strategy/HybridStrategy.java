package dev.rook.agent.strategy;

import dev.rook.agent.codemod.CodemodPipeline;
import dev.rook.agent.loop.AgentLoop;
import dev.rook.agent.loop.AgentResult;
import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.loop.TestRun;
import dev.rook.agent.loop.TraceStep;
import dev.rook.agent.model.Usage;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HybridStrategy implements Strategy {

    public static final String NAME = "agent";

    private final AgentLoop loop;
    private final SandboxPort sandbox;

    public HybridStrategy(AgentLoop loop, SandboxPort sandbox) {
        this.loop = loop;
        this.sandbox = sandbox;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public StrategyOutcome run(AgentTask task, Workspace workspace) {
        long started = System.nanoTime();
        CodemodPipeline.Result codemods =
                CodemodPipeline.run(workspace, task.events(), task.report());
        if (codemods.complete()) {
            return finishWithoutModel(workspace, started, codemods);
        }
        AgentResult result = loop.run(withRemaining(task, codemods), workspace);
        return new StrategyOutcome(
                NAME,
                workspace.diff(),
                workspace.changedPaths(),
                result.unresolved(),
                result.usage(),
                result.costMicros(),
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                result.status() == AgentResult.Status.VERIFIED,
                result.status().name(),
                result.trace());
    }

    private StrategyOutcome finishWithoutModel(
            Workspace workspace, long started, CodemodPipeline.Result codemods) {
        List<String> unresolved = new ArrayList<>();
        boolean verified = false;
        String status = "NO_CHANGES";
        List<TraceStep> trace = new ArrayList<>();
        if (!workspace.changedPaths().isEmpty()) {
            if (sandbox == null) {
                status = "UNVERIFIED";
                unresolved.add("the tests were not run");
            } else {
                TestRun tests = sandbox.run(workspace.root(), "all");
                verified = tests.passed();
                status = verified ? "VERIFIED" : "TESTS_FAILING";
                if (!verified) {
                    unresolved.add(
                            "verification fails: "
                                    + tests.describe().lines().findFirst().orElse(""));
                }
                trace.add(
                        new TraceStep(
                                1,
                                "tests",
                                "all",
                                tests.describe().lines().findFirst().orElse(""),
                                0,
                                !verified));
            }
        }
        codemods.outcomes().forEach(outcome -> unresolved.addAll(outcome.skipped()));
        return new StrategyOutcome(
                NAME,
                workspace.diff(),
                workspace.changedPaths(),
                unresolved,
                Usage.NONE,
                0,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                verified,
                status,
                trace);
    }

    private static AgentTask withRemaining(AgentTask task, CodemodPipeline.Result codemods) {
        Set<String> remaining = new HashSet<>();
        codemods.remaining().forEach(location -> remaining.add(location.key()));
        List<Impact> impacts = new ArrayList<>();
        for (Impact impact : task.report().impacts()) {
            List<ImpactLocation> left =
                    impact.locations().stream().filter(l -> remaining.contains(l.key())).toList();
            if (!left.isEmpty()) {
                impacts.add(
                        new Impact(
                                impact.fingerprint(),
                                impact.kind(),
                                impact.severity(),
                                impact.subject(),
                                impact.summary(),
                                impact.attributes(),
                                left));
            }
        }
        String automated =
                codemods.edits() == 0
                        ? ""
                        : "Automated edits already fixed "
                                + codemods.edits()
                                + " places. Line numbers below refer to the original files and the"
                                + " workspace already contains those edits. Only the places still"
                                + " listed need your attention.";
        String preface =
                task.preface().isEmpty()
                        ? automated
                        : automated.isEmpty()
                                ? task.preface()
                                : task.preface() + "\n\n" + automated;
        return new AgentTask(
                task.repository(),
                task.events(),
                ImpactReport.of(impacts),
                task.notes(),
                task.index(),
                preface);
    }
}
