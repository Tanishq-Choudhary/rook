package dev.rook.verify.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.rook.agent.loop.TestRun;
import dev.rook.agent.patch.UnifiedDiff;
import dev.rook.codeintel.project.RookConfig;
import dev.rook.verify.build.BuildPlanner;
import dev.rook.verify.gate.Gates;
import dev.rook.verify.sandbox.DockerConfig;
import dev.rook.verify.sandbox.DockerSandboxRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifierIT {

    private static final String POM =
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                    + "  <modelVersion>4.0.0</modelVersion>\n"
                    + "  <groupId>demo</groupId><artifactId>demo</artifactId><version>1</version>\n"
                    + "  <properties><maven.compiler.release>21</maven.compiler.release>"
                    + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>\n"
                    + "  <dependencies><dependency><groupId>org.junit.jupiter</groupId>"
                    + "<artifactId>junit-jupiter</artifactId><version>5.11.0</version><scope>test</scope>"
                    + "</dependency></dependencies>\n"
                    + "</project>\n";

    private static final String APP =
            "package a;\n\npublic class App {\n    public int one() {\n        return 1;\n    }\n}\n";

    private static final String TEST =
            "package a;\n\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
                    + "import org.junit.jupiter.api.Test;\n\nclass AppTest {\n    @Test\n    void one() {\n"
                    + "        assertEquals(1, new App().one());\n    }\n\n    @Test\n    void two() {\n"
                    + "        assertEquals(2, new App().one() + 1);\n    }\n}\n";

    private static final String APP_PATH = "src/main/java/a/App.java";

    private static Path cache;

    @TempDir Path temp;

    private Path baseline;
    private Path patched;
    private PhaseRunner phases;
    private Verifier verifier;

    @BeforeAll
    static void sharedCache() {
        cache = Path.of("target", "verify-cache").toAbsolutePath();
    }

    @BeforeEach
    void setUp() throws IOException {
        DockerSandboxRunner docker =
                new DockerSandboxRunner(DockerConfig.standard(), Clock.systemUTC());
        assumeTrue(docker.available(), "docker is not available");
        baseline = project("baseline", APP);
        patched = project("patched", APP);
        phases = new PhaseRunner(docker);
        verifier = new Verifier(phases, new Gates());
    }

    private Path project(String name, String app) throws IOException {
        Path root = Files.createDirectories(temp.resolve(name));
        write(root, "pom.xml", POM);
        write(root, APP_PATH, app);
        write(root, "src/test/java/a/AppTest.java", TEST);
        return root;
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private VerificationOutcome verify(String patchedApp) throws IOException {
        write(patched, APP_PATH, patchedApp);
        String diff = UnifiedDiff.generate(APP_PATH, APP, patchedApp);
        return verifier.verify(
                new VerificationRequest(
                        baseline,
                        patched,
                        diff,
                        RookConfig.defaults(),
                        cache,
                        BuildPlanner.DEFAULT_IMAGE,
                        1,
                        false));
    }

    @Test
    void aHarmlessChangePassesEveryGateInsideTheSandbox() throws IOException {
        VerificationOutcome outcome =
                verify(APP.replace("return 1;", "int result = 1;\n        return result;"));
        assertThat(outcome.passed()).as(outcome.summary()).isTrue();
        assertThat(outcome.baseline().report().executed()).isEqualTo(2);
        assertThat(outcome.patched().report().executed()).isEqualTo(2);
        assertThat(outcome.comparison().regressions()).isEmpty();
        assertThat(PhaseRunner.isWarm(cache)).isTrue();
    }

    @Test
    void aBehaviourChangeThatBreaksATestIsRejected() throws IOException {
        VerificationOutcome outcome = verify(APP.replace("return 1;", "return 5;"));
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.comparison().regressions())
                .containsExactlyInAnyOrder("a.AppTest#one", "a.AppTest#two");
        assertThat(outcome.gates().failures())
                .extracting(r -> r.name())
                .contains(Gates.NO_NEW_FAILURES);
    }

    @Test
    void codeThatDoesNotCompileIsRejected() throws IOException {
        VerificationOutcome outcome = verify(APP.replace("return 1;", "return missing();"));
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.gates().failures()).extracting(r -> r.name()).contains(Gates.COMPILES);
    }

    @Test
    void theAgentPortRunsTestsOfAWorkspaceOfflineAndFiltersByClass() throws IOException {
        DockerSandboxPort port =
                new DockerSandboxPort(
                        phases, RookConfig.defaults().verify(), cache, BuildPlanner.DEFAULT_IMAGE);
        TestRun all = port.run(baseline, "all");
        assertThat(all.passed()).as(all.describe()).isTrue();
        assertThat(all.executed()).isEqualTo(2);
        TestRun one = port.run(baseline, "AppTest#one");
        assertThat(one.passed()).as(one.describe()).isTrue();
        assertThat(one.executed()).isEqualTo(1);
    }
}
