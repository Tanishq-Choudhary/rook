package dev.rook.codeintel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoProfileTest {

    @TempDir Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void detectsSourceRootsAcrossModules() throws IOException {
        write("src/main/java/a/A.java", "package a; class A {}");
        write("src/test/java/a/ATest.java", "package a; class ATest {}");
        write("billing/src/main/java/b/B.java", "package b; class B {}");
        RepoProfile profile = RepoProfile.detect(root);
        assertThat(profile.mainRoots()).containsExactly("billing/src/main/java", "src/main/java");
        assertThat(profile.testRoots()).containsExactly("src/test/java");
    }

    @Test
    void configurationFiltersSourceRoots() throws IOException {
        write("src/main/java/a/A.java", "package a; class A {}");
        write("src/generated/src/main/java/g/G.java", "package g; class G {}");
        write(".rook.yml", "version: 1\npaths:\n  exclude: [src/generated]\n");
        RepoProfile profile = RepoProfile.detect(root);
        assertThat(profile.mainRoots()).containsExactly("src/main/java");
    }

    @Test
    void includeListRestrictsRoots() throws IOException {
        write("src/main/java/a/A.java", "package a; class A {}");
        write("billing/src/main/java/b/B.java", "package b; class B {}");
        write(".rook.yml", "version: 1\npaths:\n  include: [billing]\n");
        RepoProfile profile = RepoProfile.detect(root);
        assertThat(profile.mainRoots()).containsExactly("billing/src/main/java");
    }

    @Test
    void exposesWatchedCoordinatesAndDependencies() throws IOException {
        write(
                "pom.xml",
                "<project><groupId>g</groupId><artifactId>a</artifactId><version>1</version><dependencies><dependency><groupId>com.acme</groupId><artifactId>acme-sdk</artifactId><version>1.0.0</version></dependency></dependencies></project>");
        write(".rook.yml", "watch:\n  - source: acme-sdk\n    coordinates: com.acme:acme-sdk\n");
        RepoProfile profile = RepoProfile.detect(root);
        assertThat(profile.watches("com.acme:acme-sdk")).isTrue();
        assertThat(profile.watches("com.other:x")).isFalse();
        assertThat(profile.dependencyOn("com.acme:acme-sdk"))
                .singleElement()
                .extracting(Dependency::version)
                .isEqualTo("1.0.0");
    }

    @Test
    void repositoriesWithoutConfigurationUseDefaults() throws IOException {
        write("src/main/java/a/A.java", "package a; class A {}");
        RepoProfile profile = RepoProfile.detect(root);
        assertThat(profile.config()).isEqualTo(RookConfig.defaults());
        assertThat(profile.scan().buildTool()).isEqualTo(DependencyScanner.BuildTool.UNKNOWN);
    }
}
