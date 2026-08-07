package dev.rook.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.rook.core.error.RookException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RepoFilesTest {

    @TempDir Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeEach
    void layout() throws IOException {
        write("src/main/java/com/acme/App.java", "class App {}");
        write("src/test/java/com/acme/AppTest.java", "class AppTest {}");
        write("pom.xml", "<project/>");
        write("target/classes/Generated.java", "class Generated {}");
        write("node_modules/lib/index.js", "x");
        write(".git/config", "[core]");
        write(".env", "TOKEN=abc");
        write("keys/server.pem", "-----BEGIN-----");
    }

    @Test
    void walksSourceFilesInSortedOrder() {
        List<String> paths = RepoFiles.walk(root).stream().map(RepoFiles.RepoFile::path).toList();
        assertThat(paths)
                .containsExactly(
                        "pom.xml",
                        "src/main/java/com/acme/App.java",
                        "src/test/java/com/acme/AppTest.java");
    }

    @Test
    void skipsBuildOutputVcsAndDependencyDirectories() {
        assertThat(RepoFiles.walk(root))
                .extracting(RepoFiles.RepoFile::path)
                .noneMatch(
                        p ->
                                p.startsWith("target/")
                                        || p.startsWith(".git/")
                                        || p.startsWith("node_modules/"));
    }

    @Test
    void skipsSecretBearingFilesUnlessAsked() {
        assertThat(RepoFiles.walk(root))
                .extracting(RepoFiles.RepoFile::path)
                .doesNotContain(".env", "keys/server.pem");
        assertThat(RepoFiles.walk(root, RepoFiles.Options.defaults().includingSensitive()))
                .extracting(RepoFiles.RepoFile::path)
                .contains(".env", "keys/server.pem");
    }

    @Test
    void skipsOversizedAndBinaryFiles() throws IOException {
        write("big.txt", "x".repeat(5000));
        Files.write(root.resolve("blob.bin"), new byte[] {1, 2, 0, 3});
        List<String> paths =
                RepoFiles.walk(root, RepoFiles.Options.defaults().withMaxFileBytes(1000)).stream()
                        .map(RepoFiles.RepoFile::path)
                        .toList();
        assertThat(paths).doesNotContain("big.txt", "blob.bin");
    }

    @Test
    void reportsExtensionsFromThePath() {
        RepoFiles.RepoFile file =
                RepoFiles.walk(root).stream()
                        .filter(f -> f.path().endsWith("App.java"))
                        .findFirst()
                        .orElseThrow();
        assertThat(file.extension()).isEqualTo("java");
        assertThat(new RepoFiles.RepoFile("Makefile", root, 1).extension()).isEmpty();
        assertThat(new RepoFiles.RepoFile("dir.d/file", root, 1).extension()).isEmpty();
    }

    @Test
    void symlinkedDirectoriesAreNotFollowed() throws IOException {
        Path outside = Files.createTempDirectory("outside");
        Files.writeString(outside.resolve("leak.java"), "class Leak {}");
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here");
        }
        assertThat(RepoFiles.walk(root))
                .extracting(RepoFiles.RepoFile::path)
                .noneMatch(p -> p.contains("leak"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../secret.txt",
                "a/../../secret.txt",
                "/etc/passwd",
                "C:/Windows/win.ini",
                "..\\secret.txt",
                "src/../../outside"
            })
    void resolveInsideRejectsEscapes(String path) {
        assertThatThrownBy(() -> RepoFiles.resolveInside(root, path))
                .isInstanceOf(RookException.class);
    }

    @Test
    void resolveInsideRejectsNullBytesAndBlank() {
        assertThatThrownBy(() -> RepoFiles.resolveInside(root, "a\0b"))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> RepoFiles.resolveInside(root, " "))
                .isInstanceOf(RookException.class);
    }

    @Test
    void resolveInsideAcceptsNestedRelativePaths() {
        assertThat(RepoFiles.resolveInside(root, "src/main/java/com/acme/App.java"))
                .isEqualTo(root.resolve("src/main/java/com/acme/App.java").normalize());
        assertThat(RepoFiles.resolveInside(root, "src/./main/../main/java"))
                .isEqualTo(root.resolve("src/main/java"));
    }

    @Test
    void resolveInsideRejectsLinksThatLeaveTheRepository() throws IOException {
        Path outside = Files.createTempDirectory("outside");
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available here");
        }
        assertThatThrownBy(() -> RepoFiles.resolveInside(root, "escape/secret.txt"))
                .isInstanceOf(RookException.class);
    }

    @Test
    void readReturnsTextAndEnforcesLimits() throws IOException {
        assertThat(RepoFiles.read(root, "pom.xml", 1000)).isEqualTo("<project/>");
        write("large.txt", "y".repeat(2000));
        assertThatThrownBy(() -> RepoFiles.read(root, "large.txt", 100))
                .isInstanceOf(RookException.class);
        Files.write(root.resolve("blob.bin"), new byte[] {65, 0, 66});
        assertThatThrownBy(() -> RepoFiles.read(root, "blob.bin", 100))
                .hasMessageContaining("binary");
        assertThatThrownBy(() -> RepoFiles.read(root, "missing.txt", 100))
                .isInstanceOf(RookException.class);
    }
}
