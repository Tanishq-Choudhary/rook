package dev.rook.codeintel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyScannerTest {

    @TempDir Path root;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String pom(String artifact, String dependencies) {
        return "<project><groupId>g</groupId><artifactId>"
                + artifact
                + "</artifactId><version>1</version><dependencies>"
                + dependencies
                + "</dependencies></project>";
    }

    private static String dep(String group, String artifact, String version) {
        return "<dependency><groupId>"
                + group
                + "</groupId><artifactId>"
                + artifact
                + "</artifactId><version>"
                + version
                + "</version></dependency>";
    }

    @Test
    void aggregatesDependenciesAcrossModules() throws IOException {
        write("pom.xml", pom("root", dep("com.acme", "acme-sdk", "1.0.0")));
        write(
                "billing/pom.xml",
                pom("billing", dep("com.acme", "acme-sdk", "1.0.0") + dep("org.x", "y", "2")));
        DependencyScanner.Scan scan = DependencyScanner.scan(root);
        assertThat(scan.buildTool()).isEqualTo(DependencyScanner.BuildTool.MAVEN);
        assertThat(scan.dependencies())
                .extracting(Dependency::declaredIn)
                .contains("pom.xml", "billing/pom.xml");
        assertThat(scan.dependencies())
                .extracting(Dependency::coordinates)
                .contains("com.acme:acme-sdk", "org.x:y");
    }

    @Test
    void brokenPomsProduceWarningsNotFailures() throws IOException {
        write("pom.xml", pom("root", dep("com.acme", "acme-sdk", "1.0.0")));
        write("broken/pom.xml", "<project><unclosed>");
        DependencyScanner.Scan scan = DependencyScanner.scan(root);
        assertThat(scan.dependencies()).hasSize(1);
        assertThat(scan.warnings()).singleElement().asString().startsWith("broken/pom.xml");
    }

    @Test
    void readsGradleStyleDeclarations() throws IOException {
        write(
                "build.gradle",
                "dependencies {\n    implementation 'com.acme:acme-sdk:1.2.3'\n    testImplementation(\"org.junit:junit:5.0\")\n    api \"a:b:${libVersion}\"\n}\n");
        DependencyScanner.Scan scan = DependencyScanner.scan(root);
        assertThat(scan.buildTool()).isEqualTo(DependencyScanner.BuildTool.GRADLE);
        Dependency acme =
                scan.dependencies().stream()
                        .filter(d -> d.artifactId().equals("acme-sdk"))
                        .findFirst()
                        .orElseThrow();
        assertThat(acme.version()).isEqualTo("1.2.3");
        Dependency junit =
                scan.dependencies().stream()
                        .filter(d -> d.artifactId().equals("junit"))
                        .findFirst()
                        .orElseThrow();
        assertThat(junit.scope()).isEqualTo("test");
        Dependency templated =
                scan.dependencies().stream()
                        .filter(d -> d.artifactId().equals("b"))
                        .findFirst()
                        .orElseThrow();
        assertThat(templated.version()).isNull();
    }

    @Test
    void emptyRepositoriesHaveNoBuildTool() {
        DependencyScanner.Scan scan = DependencyScanner.scan(root);
        assertThat(scan.buildTool()).isEqualTo(DependencyScanner.BuildTool.UNKNOWN);
        assertThat(scan.dependencies()).isEmpty();
    }

    @Test
    void ignoresPomsInsideBuildOutput() throws IOException {
        write("pom.xml", pom("root", dep("com.acme", "acme-sdk", "1.0.0")));
        write("target/generated/pom.xml", pom("gen", dep("evil", "evil", "1")));
        assertThat(DependencyScanner.scan(root).dependencies())
                .extracting(Dependency::groupId)
                .doesNotContain("evil");
    }
}
