package dev.rook.eval.verify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.generate.FixtureGenerator;
import dev.rook.verify.sandbox.LocalProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureGoldIT {

    @TempDir static Path shared;

    static List<Fixture> fixtures;
    static LocalMavenVerifier verifier;

    @BeforeAll
    static void setUp() {
        fixtures = FixtureGenerator.generate(shared.resolve("fixtures"));
        verifier =
                new LocalMavenVerifier(
                        LocalProcessRunner.create(true),
                        shared.resolve("scratch"),
                        Duration.ofMinutes(4));
    }

    @Test
    void everyFixtureIsHealthyBeforeTheUpgradeBrokenAfterItAndFixedByTheGoldPatch() {
        Map<String, String> problems = new ConcurrentHashMap<>();
        fixtures.parallelStream()
                .forEach(
                        fixture -> {
                            Verdict base =
                                    verifier.verify(fixture, fixture.repo(), SdkVersion.BEFORE);
                            if (!base.accepted()) {
                                problems.put(fixture.id(), "base " + describe(base));
                                return;
                            }
                            Verdict upgraded =
                                    verifier.verify(fixture, fixture.repo(), SdkVersion.AFTER);
                            if (upgraded.accepted() != fixture.spec().noop()) {
                                problems.put(fixture.id(), "upgraded " + describe(upgraded));
                                return;
                            }
                            Path gold =
                                    FixtureProject.withGold(
                                            fixture, shared.resolve("gold").resolve(fixture.id()));
                            Verdict fixed = verifier.verify(fixture, gold, SdkVersion.AFTER);
                            if (!fixed.accepted()) {
                                problems.put(fixture.id(), "gold " + describe(fixed));
                            }
                        });
        assertThat(problems).isEmpty();
    }

    private static String describe(Verdict verdict) {
        return "compiled="
                + verdict.compiled()
                + " visible="
                + verdict.visibleFailures()
                + " independent="
                + verdict.independentFailures()
                + " "
                + verdict.detail();
    }
}
