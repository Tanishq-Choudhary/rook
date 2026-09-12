package dev.rook.verify.run;

import dev.rook.codeintel.project.RookConfig;
import java.nio.file.Path;

public record VerificationRequest(
        Path baselineSource,
        Path patchedSource,
        String diff,
        RookConfig config,
        Path cacheDir,
        String image,
        int flakeReruns,
        boolean allowBuildFiles) {

    public VerificationRequest {
        if (flakeReruns < 0) {
            throw new IllegalArgumentException("flakeReruns cannot be negative");
        }
    }
}
