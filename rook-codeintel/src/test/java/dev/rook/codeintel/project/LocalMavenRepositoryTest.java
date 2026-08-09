package dev.rook.codeintel.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMavenRepositoryTest {

    @TempDir Path repository;

    private Path install(String group, String artifact, String version) throws IOException {
        Path jar =
                repository
                        .resolve(group.replace('.', '/'))
                        .resolve(artifact)
                        .resolve(version)
                        .resolve(artifact + "-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        return jar;
    }

    @Test
    void locatesInstalledJars() throws IOException {
        Path jar = install("com.acme", "acme-sdk", "1.2.3");
        LocalMavenRepository local = new LocalMavenRepository(repository);
        assertThat(local.jar(new Dependency("com.acme", "acme-sdk", "1.2.3", "compile", "pom.xml")))
                .contains(jar);
    }

    @Test
    void missingJarsAndUnknownVersionsAreEmpty() {
        LocalMavenRepository local = new LocalMavenRepository(repository);
        assertThat(local.jar(new Dependency("com.acme", "absent", "1.0", "compile", "pom.xml")))
                .isEmpty();
        assertThat(local.jar(new Dependency("com.acme", "acme-sdk", null, "compile", "pom.xml")))
                .isEmpty();
    }

    @Test
    void jarsCollectsTheDistinctExistingOnes() throws IOException {
        install("com.acme", "a", "1");
        LocalMavenRepository local = new LocalMavenRepository(repository);
        List<Path> jars =
                local.jars(
                        List.of(
                                new Dependency("com.acme", "a", "1", "compile", "pom.xml"),
                                new Dependency("com.acme", "a", "1", "test", "other/pom.xml"),
                                new Dependency("com.acme", "b", "1", "compile", "pom.xml")));
        assertThat(jars).hasSize(1);
    }
}
