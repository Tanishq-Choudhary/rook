package dev.rook.verify.run;

import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.loop.TestRun;
import dev.rook.codeintel.project.RookConfig;
import dev.rook.core.error.RookException;
import dev.rook.verify.build.BuildPlan;
import dev.rook.verify.build.BuildPlanner;
import dev.rook.verify.build.RunSummary;
import java.nio.file.Path;

public final class DockerSandboxPort implements SandboxPort {

    private final PhaseRunner phases;
    private final RookConfig.Verify verify;
    private final Path cache;
    private final String image;

    public DockerSandboxPort(
            PhaseRunner phases, RookConfig.Verify verify, Path cache, String image) {
        this.phases = phases;
        this.verify = verify;
        this.cache = cache;
        this.image = image;
    }

    @Override
    public TestRun run(Path workspaceRoot, String scope) {
        BuildPlanner.Result planned;
        try {
            planned = BuildPlanner.detect(workspaceRoot, verify, image);
        } catch (RookException e) {
            return TestRun.notCompiling("verification is not possible: " + e.getMessage());
        }
        if (planned instanceof BuildPlanner.Unsupported unsupported) {
            return TestRun.notCompiling("verification is not possible: " + unsupported.reason());
        }
        BuildPlan plan = ((BuildPlanner.Planned) planned).plan();
        String filter = scope == null || scope.equals("all") ? null : scope;
        RunSummary summary =
                PhaseRunner.isWarm(cache)
                        ? phases.test(plan, workspaceRoot, cache, filter)
                        : phases.warm(plan, workspaceRoot, cache);
        return summary.toAgentRun();
    }
}
