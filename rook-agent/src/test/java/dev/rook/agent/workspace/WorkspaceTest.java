package dev.rook.agent.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.rook.agent.patch.PatchRejected;
import dev.rook.agent.patch.UnifiedDiff;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @TempDir Path temp;

    private Path source;
    private Path target;

    @BeforeEach
    void setUp() throws IOException {
        source = Files.createDirectories(temp.resolve("source"));
        target = temp.resolve("work");
        write(
                source,
                "src/main/java/App.java",
                "class App {\n    int value() {\n        return 1;\n    }\n}\n");
        write(source, "src/test/java/AppTest.java", "class AppTest {}\n");
        write(source, "pom.xml", "<project/>\n");
        write(source, ".github/workflows/ci.yml", "name: ci\n");
        write(source, ".rook.yml", "version: 1\n");
        write(source, ".env", "SECRET=1\n");
        write(source, ".git/config", "[core]\n");
        write(source, "target/classes/App.class", "x");
        write(source, "config/credentials.json", "{}\n");
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Workspace workspace() {
        return Workspace.copyOf(source, target, WorkspacePolicy.standard());
    }

    private static WorkspaceViolation.Code violation(Runnable action) {
        try {
            action.run();
        } catch (WorkspaceViolation e) {
            return e.code();
        }
        throw new AssertionError("expected a violation");
    }

    @Test
    void theCopyLeavesOutMetadataBuildOutputAndSecrets() {
        Workspace workspace = workspace();
        assertThat(workspace.list("", 100))
                .contains(
                        "src/main/java/App.java",
                        "src/test/java/AppTest.java",
                        "pom.xml",
                        ".github/workflows/ci.yml",
                        ".rook.yml")
                .doesNotContain(".env", ".git/config", "target/classes/App.class")
                .doesNotContain("config/credentials.json");
    }

    @Test
    void theCopyRefusesRepositoriesWithTooManyFiles() {
        assertThat(
                        violation(
                                () ->
                                        Workspace.copyOf(
                                                source,
                                                target,
                                                WorkspacePolicy.standard().withMaxCopiedFiles(2))))
                .isEqualTo(WorkspaceViolation.Code.LIMIT_REACHED);
    }

    @Test
    void listFiltersByPrefixAndHonoursTheLimit() {
        Workspace workspace = workspace();
        assertThat(workspace.list("src/test", 10)).containsExactly("src/test/java/AppTest.java");
        assertThat(workspace.list("", 2)).hasSize(2);
        assertThat(workspace.list(null, 100)).isNotEmpty();
    }

    @Test
    void readReturnsTheTextAndNumberedRanges() {
        Workspace workspace = workspace();
        assertThat(workspace.read("src/main/java/App.java")).startsWith("class App {");
        assertThat(workspace.readLines("src/main/java/App.java", 2, 3))
                .isEqualTo("2:     int value() {\n3:         return 1;\n");
        assertThat(workspace.readLines("src/main/java/App.java", 5, 99)).isEqualTo("5: }\n");
        assertThat(violation(() -> workspace.readLines("pom.xml", 0, 1)))
                .isEqualTo(WorkspaceViolation.Code.LIMIT_REACHED);
    }

    @Test
    void pathsOutsideTheRootAreRefused() {
        Workspace workspace = workspace();
        for (String path :
                List.of("../secret.txt", "/etc/passwd", "C:\\Windows\\win.ini", "src/../../x")) {
            assertThat(violation(() -> workspace.read(path)))
                    .as(path)
                    .isEqualTo(WorkspaceViolation.Code.OUTSIDE_ROOT);
        }
    }

    @Test
    void aSymlinkLeavingTheWorkspaceIsRefused() throws IOException {
        Path outside = Files.writeString(temp.resolve("outside.txt"), "private\n");
        Workspace workspace = workspace();
        try {
            Files.createSymbolicLink(target.resolve("link.txt"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available here");
        }
        assertThat(violation(() -> workspace.read("link.txt")))
                .isEqualTo(WorkspaceViolation.Code.OUTSIDE_ROOT);
    }

    @Test
    void secretsAndRepositoryMetadataCannotBeRead() {
        Workspace workspace = Workspace.over(source, WorkspacePolicy.standard());
        for (String path : List.of(".env", ".git/config", "config/credentials.json")) {
            assertThat(violation(() -> workspace.read(path)))
                    .as(path)
                    .isEqualTo(WorkspaceViolation.Code.DENIED);
        }
    }

    @Test
    void missingBinaryAndOversizedFilesAreReportedDistinctly() throws IOException {
        Files.write(source.resolve("data.bin"), new byte[] {1, 0, 2});
        Files.write(source.resolve("latin.txt"), "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1));
        Files.writeString(source.resolve("big.txt"), "x".repeat(2000));
        Workspace workspace =
                Workspace.over(source, WorkspacePolicy.standard().withMaxFileBytes(1000));
        assertThat(violation(() -> workspace.read("nothing.txt")))
                .isEqualTo(WorkspaceViolation.Code.NOT_FOUND);
        assertThat(violation(() -> workspace.read("data.bin")))
                .isEqualTo(WorkspaceViolation.Code.NOT_TEXT);
        assertThat(violation(() -> workspace.read("latin.txt")))
                .isEqualTo(WorkspaceViolation.Code.NOT_TEXT);
        assertThat(violation(() -> workspace.read("big.txt")))
                .isEqualTo(WorkspaceViolation.Code.TOO_LARGE);
    }

    @Test
    void anEditChangesTheFileAndProducesARecordAndDiff() throws IOException {
        Workspace workspace = workspace();
        Workspace.EditRecord record =
                workspace.edit("src/main/java/App.java", "return 1;", "return 2;");
        assertThat(record.sequence()).isEqualTo(1);
        assertThat(record.line()).isEqualTo(3);
        assertThat(Files.readString(target.resolve("src/main/java/App.java")))
                .contains("return 2;");
        assertThat(workspace.changedPaths()).containsExactly("src/main/java/App.java");
        assertThat(workspace.changedLines()).isEqualTo(2);
        assertThat(workspace.log()).hasSize(1);
        String original = Files.readString(source.resolve("src/main/java/App.java"));
        assertThat(UnifiedDiff.apply(original, workspace.diff()))
                .isEqualTo(Files.readString(target.resolve("src/main/java/App.java")));
    }

    @Test
    void severalEditsToOneFileAreFoldedIntoOneDiff() {
        Workspace workspace = workspace();
        workspace.edit("src/main/java/App.java", "class App", "class Application");
        workspace.edit("src/main/java/App.java", "return 1;", "return 3;");
        assertThat(workspace.changedPaths()).hasSize(1);
        assertThat(workspace.diff().lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(1);
        assertThat(workspace.log()).hasSize(2);
    }

    @Test
    void anEditThatIsRevertedLeavesNoChange() {
        Workspace workspace = workspace();
        workspace.edit("src/main/java/App.java", "return 1;", "return 9;");
        workspace.edit("src/main/java/App.java", "return 9;", "return 1;");
        assertThat(workspace.changedPaths()).isEmpty();
        assertThat(workspace.diff()).isEmpty();
        assertThat(workspace.changedFileCount()).isZero();
    }

    @Test
    void rejectedEditsLeaveTheFileUntouched() throws IOException {
        Workspace workspace = workspace();
        String before = Files.readString(target.resolve("src/main/java/App.java"));
        assertThatThrownBy(() -> workspace.edit("src/main/java/App.java", "missing text", "x"))
                .isInstanceOf(PatchRejected.class);
        assertThat(Files.readString(target.resolve("src/main/java/App.java"))).isEqualTo(before);
        assertThat(workspace.log()).isEmpty();
    }

    @Test
    void workflowsRookConfigAndBuildFilesCannotBeEdited() {
        Workspace workspace = workspace();
        for (String path : List.of(".github/workflows/ci.yml", ".rook.yml", "pom.xml")) {
            assertThat(violation(() -> workspace.edit(path, "n", "m")))
                    .as(path)
                    .isEqualTo(WorkspaceViolation.Code.DENIED);
        }
        assertThat(workspace.changedPaths()).isEmpty();
    }

    @Test
    void buildFilesCanBeEditedWhenThePolicyAllowsIt() {
        Workspace workspace =
                Workspace.copyOf(source, target, WorkspacePolicy.standard().allowingBuildFiles());
        workspace.edit("pom.xml", "<project/>", "<project></project>");
        assertThat(workspace.changedPaths()).containsExactly("pom.xml");
    }

    @Test
    void theEditLimitStopsFurtherEdits() {
        Workspace workspace =
                Workspace.copyOf(source, target, WorkspacePolicy.standard().withLimits(5, 2, 100));
        workspace.edit("src/main/java/App.java", "return 1;", "return 2;");
        workspace.edit("src/main/java/App.java", "return 2;", "return 3;");
        assertThat(
                        violation(
                                () ->
                                        workspace.edit(
                                                "src/main/java/App.java",
                                                "return 3;",
                                                "return 4;")))
                .isEqualTo(WorkspaceViolation.Code.LIMIT_REACHED);
    }

    @Test
    void theFileLimitStopsEditsToNewFiles() {
        Workspace workspace =
                Workspace.copyOf(source, target, WorkspacePolicy.standard().withLimits(1, 10, 100));
        workspace.edit("src/main/java/App.java", "return 1;", "return 2;");
        assertThat(
                        violation(
                                () ->
                                        workspace.edit(
                                                "src/test/java/AppTest.java", "AppTest", "Other")))
                .isEqualTo(WorkspaceViolation.Code.LIMIT_REACHED);
        workspace.edit("src/main/java/App.java", "return 2;", "return 3;");
    }

    @Test
    void theChangedLineLimitRejectsAnEditThatWouldCrossIt() throws IOException {
        Workspace workspace =
                Workspace.copyOf(source, target, WorkspacePolicy.standard().withLimits(5, 10, 3));
        workspace.edit("src/main/java/App.java", "return 1;", "return 2;");
        String before = Files.readString(target.resolve("src/main/java/App.java"));
        assertThat(
                        violation(
                                () ->
                                        workspace.edit(
                                                "src/main/java/App.java",
                                                "class App {",
                                                "class App { // x\n  int a;\n  int b;")))
                .isEqualTo(WorkspaceViolation.Code.LIMIT_REACHED);
        assertThat(Files.readString(target.resolve("src/main/java/App.java"))).isEqualTo(before);
    }

    @Test
    void anEditThatGrowsAFilePastTheSizeLimitIsRejected() {
        Workspace workspace =
                Workspace.copyOf(source, target, WorkspacePolicy.standard().withMaxFileBytes(120));
        assertThat(
                        violation(
                                () ->
                                        workspace.edit(
                                                "src/main/java/App.java",
                                                "return 1;",
                                                "x".repeat(200))))
                .isEqualTo(WorkspaceViolation.Code.TOO_LARGE);
    }

    @Test
    void overRejectsARootThatIsNotADirectory() {
        assertThat(
                        violation(
                                () ->
                                        Workspace.over(
                                                source.resolve("pom.xml"),
                                                WorkspacePolicy.standard())))
                .isEqualTo(WorkspaceViolation.Code.NOT_FOUND);
    }

    @Test
    void policyLimitsMustBePositive() {
        assertThatThrownBy(() -> WorkspacePolicy.standard().withLimits(0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void originalsAreSnapshotsOfTheFirstTouch() {
        Workspace workspace = workspace();
        workspace.edit("src/main/java/App.java", "return 1;", "return 2;");
        workspace.edit("src/main/java/App.java", "return 2;", "return 3;");
        assertThat(workspace.originals().get("src/main/java/App.java")).contains("return 1;");
        assertThat(workspace.root()).isEqualTo(target.toAbsolutePath().normalize());
    }

    @Test
    void rewriteReplacesTheWholeFileAndCountsAsOneEdit() throws IOException {
        Workspace workspace = workspace();
        Workspace.EditRecord record =
                workspace.rewrite(
                        "src/main/java/App.java",
                        "class App {\n    int value() {\n        return 5;\n    }\n}\n");
        assertThat(record.line()).isEqualTo(3);
        assertThat(Files.readString(target.resolve("src/main/java/App.java")))
                .contains("return 5;");
        assertThat(workspace.changedPaths()).containsExactly("src/main/java/App.java");
        assertThat(workspace.log()).hasSize(1);
    }

    @Test
    void rewriteWithIdenticalContentIsRejected() throws IOException {
        Workspace workspace = workspace();
        String same = Files.readString(target.resolve("src/main/java/App.java"));
        assertThatThrownBy(() -> workspace.rewrite("src/main/java/App.java", same))
                .isInstanceOf(PatchRejected.class);
    }

    @Test
    void rewriteFollowsTheSameDenialAndLimitRules() {
        Workspace workspace =
                Workspace.copyOf(source, target, WorkspacePolicy.standard().withLimits(5, 10, 2));
        assertThat(violation(() -> workspace.rewrite("pom.xml", "<project></project>\n")))
                .isEqualTo(WorkspaceViolation.Code.DENIED);
        assertThat(
                        violation(
                                () ->
                                        workspace.rewrite(
                                                "src/main/java/App.java",
                                                "class Other {\n    int a;\n    int b;\n    int c;\n}\n")))
                .isEqualTo(WorkspaceViolation.Code.LIMIT_REACHED);
    }
}
