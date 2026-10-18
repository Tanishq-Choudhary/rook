package dev.rook.eval.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartialResultsTest {

    private static FixtureResult result(String strategy, String id, boolean accepted) {
        return new FixtureResult(
                id,
                "method-rename",
                "easy",
                false,
                strategy,
                "NEEDS_MIGRATION",
                2,
                0,
                0,
                true,
                List.of("src/main/java/app/Service.java"),
                List.of(),
                true,
                accepted,
                false,
                accepted,
                0,
                1234,
                "VERIFIED",
                List.of("one thing left"),
                "");
    }

    @Test
    void aSavedResultComesBackUnchanged(@TempDir Path temp) {
        PartialResults partial = new PartialResults(temp.resolve("partial"));
        FixtureResult saved = result("agent", "method-rename-pay-field", true);

        partial.save(saved);

        assertThat(partial.load("agent", "method-rename-pay-field")).contains(saved);
    }

    @Test
    void resultsAreKeptPerStrategyAndFixture(@TempDir Path temp) {
        PartialResults partial = new PartialResults(temp.resolve("partial"));
        partial.save(result("agent", "a", true));

        assertThat(partial.load("agent", "a")).isPresent();
        assertThat(partial.load("llm-only", "a")).isEmpty();
        assertThat(partial.load("agent", "b")).isEmpty();
    }

    @Test
    void anUnreadableFileCountsAsNotDone(@TempDir Path temp) throws IOException {
        Path root = temp.resolve("partial");
        PartialResults partial = new PartialResults(root);
        Files.createDirectories(root.resolve("agent"));
        Files.writeString(root.resolve("agent").resolve("a.json"), "{not json");

        assertThat(partial.load("agent", "a")).isEmpty();
    }

    @Test
    void clearRemovesEverythingAndToleratesAMissingFolder(@TempDir Path temp) {
        Path root = temp.resolve("partial");
        PartialResults partial = new PartialResults(root);
        partial.clear();
        partial.save(result("agent", "a", true));
        partial.save(result("llm-only", "b", false));

        partial.clear();

        assertThat(root).doesNotExist();
        assertThat(partial.load("agent", "a")).isEmpty();
    }
}
