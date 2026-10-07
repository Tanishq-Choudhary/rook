package dev.rook.server.github;

import dev.rook.pipeline.verify.PatchVerifier;
import dev.rook.verify.run.VerificationOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class MeteredPatchVerifier implements PatchVerifier {

    private final PatchVerifier delegate;
    private final MeterRegistry registry;

    public MeteredPatchVerifier(PatchVerifier delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public VerificationOutcome verify(Input input) {
        Timer.Sample sample = Timer.start(registry);
        String result = "error";
        try {
            VerificationOutcome outcome = delegate.verify(input);
            result = !outcome.supported() ? "unsupported" : outcome.passed() ? "passed" : "failed";
            return outcome;
        } finally {
            sample.stop(
                    Timer.builder("rook.verification.duration")
                            .description("time spent verifying one patch in the sandbox")
                            .tag("result", result)
                            .publishPercentileHistogram()
                            .register(registry));
            Counter.builder("rook.verifications")
                    .description("patch verifications by result")
                    .tag("result", result)
                    .register(registry)
                    .increment();
        }
    }
}
