package dev.rook.eval.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.impact.Confidence;
import dev.rook.core.change.ChangeEvent;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.generate.FixtureGenerator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureAnalysisTest {

    @TempDir static Path shared;

    static List<Fixture> fixtures;
    static final Map<String, FixtureAnalysis> RESULTS = new ConcurrentHashMap<>();

    @BeforeAll
    static void analyzeEverything() {
        fixtures = FixtureGenerator.generate(shared.resolve("fixtures"));
        fixtures.parallelStream()
                .forEach(
                        fixture ->
                                RESULTS.put(
                                        fixture.id(),
                                        FixtureAnalysis.run(
                                                fixture,
                                                shared.resolve("work").resolve(fixture.id()),
                                                Confidence.HEURISTIC)));
    }

    @Test
    void detectedChangeKindsMatchTheRecipe() {
        List<String> mismatches = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            List<String> kinds =
                    RESULTS.get(fixture.id()).change().events().stream()
                            .map(event -> event.kind().name())
                            .distinct()
                            .sorted()
                            .toList();
            if (!kinds.equals(fixture.spec().changeKinds().stream().sorted().toList())) {
                mismatches.add(fixture.id() + " -> " + kinds);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void impactMatchesTheExpectedLocationsExactly() {
        Map<String, String> misses = new TreeMap<>();
        for (Fixture fixture : fixtures) {
            ImpactScore score = RESULTS.get(fixture.id()).score();
            if (!score.exact()) {
                misses.put(
                        fixture.id(), "missed " + score.missed() + " spurious " + score.spurious());
            }
        }
        assertThat(misses).isEmpty();
    }

    @Test
    void renamesCarryTheReplacementName() {
        for (Fixture fixture : fixtures) {
            if (!fixture.spec().category().equals("method-rename")) {
                continue;
            }
            ChangeEvent event = RESULTS.get(fixture.id()).change().events().get(0);
            assertThat(event.attribute("replacement")).isNotBlank();
        }
    }
}
