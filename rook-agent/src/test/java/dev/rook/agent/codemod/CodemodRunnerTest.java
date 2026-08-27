package dev.rook.agent.codemod;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.ImpactLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodemodRunnerTest {

    @TempDir Path temp;

    private CodemodFixtures fixtures;

    @BeforeEach
    void setUp() throws IOException {
        fixtures = new CodemodFixtures(temp);
        fixtures.write(
                "src/main/java/A.java",
                "class A {\n    void go(Svc s) {\n        s.old();\n    }\n}\n");
        fixtures.write(
                "src/main/java/B.java",
                "class B {\n    void go(Svc s) {\n        s.old();\n    }\n}\n");
    }

    private static final List<ImpactLocation> LOCATIONS =
            List.of(
                    CodemodFixtures.location("src/main/java/A.java", 3, 9, "METHOD_CALL"),
                    CodemodFixtures.location("src/main/java/B.java", 3, 9, "METHOD_CALL"));

    @Test
    void anOutcomeSummarisesFilesEditsAndConfidence() {
        Workspace workspace = fixtures.workspace();
        CodemodRunner.Outcome outcome =
                CodemodRunner.run(workspace, new RenameMethodCodemod("old", "fresh"), LOCATIONS);
        assertThat(outcome.files()).isEqualTo(2);
        assertThat(outcome.edits()).isEqualTo(2);
        assertThat(outcome.skipped()).isEmpty();
        assertThat(outcome.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(outcome.codemod()).isEqualTo("rename-method old -> fresh");
        assertThat(workspace.changedPaths())
                .containsExactly("src/main/java/A.java", "src/main/java/B.java");
    }

    @Test
    void aRewriteTheWorkspaceRefusesIsReportedAndTheRestStillApply() {
        Workspace workspace = fixtures.workspace(WorkspacePolicy.standard().withLimits(1, 10, 100));
        CodemodRunner.Outcome outcome =
                CodemodRunner.run(workspace, new RenameMethodCodemod("old", "fresh"), LOCATIONS);
        assertThat(outcome.files()).isEqualTo(1);
        assertThat(outcome.skipped()).singleElement().asString().contains("src/main/java/B.java");
        assertThat(workspace.changedPaths()).containsExactly("src/main/java/A.java");
    }

    @Test
    void noLocationsMeansNothingHappens() {
        Workspace workspace = fixtures.workspace();
        CodemodRunner.Outcome outcome =
                CodemodRunner.run(workspace, new RenameMethodCodemod("old", "fresh"), List.of());
        assertThat(outcome.files()).isZero();
        assertThat(outcome.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(workspace.changedPaths()).isEmpty();
    }
}
