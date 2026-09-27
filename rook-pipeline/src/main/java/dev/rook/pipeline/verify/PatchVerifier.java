package dev.rook.pipeline.verify;

import dev.rook.codeintel.project.RookConfig;
import dev.rook.verify.run.VerificationOutcome;
import java.nio.file.Path;
import java.util.UUID;

public interface PatchVerifier {

    record Input(
            Path baseline,
            Path patched,
            String diff,
            UUID installationId,
            UUID repositoryId,
            RookConfig config,
            boolean allowBuildFiles) {}

    VerificationOutcome verify(Input input);
}
