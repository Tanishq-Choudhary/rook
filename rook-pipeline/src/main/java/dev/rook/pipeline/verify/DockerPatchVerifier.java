package dev.rook.pipeline.verify;

import dev.rook.verify.run.VerificationOutcome;
import dev.rook.verify.run.VerificationRequest;
import dev.rook.verify.run.Verifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DockerPatchVerifier implements PatchVerifier {

    private final Verifier verifier;
    private final Path cacheRoot;
    private final String image;
    private final int flakeReruns;

    public DockerPatchVerifier(Verifier verifier, Path cacheRoot, String image, int flakeReruns) {
        this.verifier = verifier;
        this.cacheRoot = cacheRoot;
        this.image = image;
        this.flakeReruns = flakeReruns;
    }

    @Override
    public VerificationOutcome verify(Input input) {
        Path cache =
                cacheRoot
                        .resolve(input.installationId().toString())
                        .resolve(input.repositoryId().toString());
        try {
            Files.createDirectories(cache);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return verifier.verify(
                new VerificationRequest(
                        input.baseline(),
                        input.patched(),
                        input.diff(),
                        input.config(),
                        cache,
                        image,
                        flakeReruns,
                        input.allowBuildFiles()));
    }
}
