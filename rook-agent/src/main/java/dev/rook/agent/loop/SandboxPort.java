package dev.rook.agent.loop;

import java.nio.file.Path;

public interface SandboxPort {

    TestRun run(Path workspaceRoot, String scope);
}
