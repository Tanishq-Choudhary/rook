package dev.rook.server.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.pipeline.verify.PatchVerifier;
import dev.rook.verify.gate.GateReport;
import dev.rook.verify.gate.GateResult;
import dev.rook.verify.run.VerificationOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeteredPatchVerifierTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static VerificationOutcome outcome(boolean passed) {
        return new VerificationOutcome(
                passed,
                null,
                new GateReport(List.of(new GateResult("compiles", passed, ""))),
                null,
                null,
                null,
                null,
                false,
                Duration.ofSeconds(2));
    }

    private double count(String result) {
        return registry.get("rook.verifications").tag("result", result).counter().count();
    }

    private MeteredPatchVerifier verifier(PatchVerifier delegate) {
        return new MeteredPatchVerifier(delegate, registry);
    }

    @Test
    void countsPassedFailedAndUnsupportedSeparately() {
        verifier(input -> outcome(true)).verify(null);
        verifier(input -> outcome(true)).verify(null);
        verifier(input -> outcome(false)).verify(null);
        verifier(input -> VerificationOutcome.unsupported("no build file", Duration.ZERO))
                .verify(null);

        assertThat(count("passed")).isEqualTo(2);
        assertThat(count("failed")).isEqualTo(1);
        assertThat(count("unsupported")).isEqualTo(1);
        assertThat(
                        registry.get("rook.verification.duration")
                                .tag("result", "passed")
                                .timer()
                                .count())
                .isEqualTo(2);
    }

    @Test
    void recordsAnErrorAndStillThrows() {
        IllegalStateException failure = new IllegalStateException("docker is down");
        MeteredPatchVerifier verifier =
                verifier(
                        input -> {
                            throw failure;
                        });

        assertThatThrownBy(() -> verifier.verify(null)).isSameAs(failure);
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void returnsTheDelegateOutcomeUnchanged() {
        VerificationOutcome expected = outcome(true);
        assertThat(verifier(input -> expected).verify(null)).isSameAs(expected);
    }
}
