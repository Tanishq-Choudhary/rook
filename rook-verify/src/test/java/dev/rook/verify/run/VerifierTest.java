package dev.rook.verify.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.loop.TestRun;
import dev.rook.agent.patch.UnifiedDiff;
import dev.rook.codeintel.project.RookConfig;
import dev.rook.verify.build.RunSummary;
import dev.rook.verify.gate.Gates;
import dev.rook.verify.sandbox.NetworkMode;
import dev.rook.verify.sandbox.SandboxResult;
import dev.rook.verify.sandbox.SandboxRunner;
import dev.rook.verify.sandbox.SandboxSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifierTest {

    private static final String SOURCE_BEFORE =
            "class A {\n    int one() {\n        return 1;\n    }\n}\n";
    private static final String SOURCE_AFTER =
            "class A {\n    int one() {\n        return 2;\n    }\n}\n";

    @TempDir Path temp;

    private Path baseline;
    private Path patched;
    private Path cache;
    private final List<SandboxSpec> specs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        baseline = Files.createDirectories(temp.resolve("baseline"));
        patched = Files.createDirectories(temp.resolve("patched"));
        cache = temp.resolve("cache");
        Files.writeString(baseline.resolve("pom.xml"), "<project/>");
        Files.writeString(patched.resolve("pom.xml"), "<project/>");
    }

    private static String junit(String... idsAndOutcomes) {
        StringBuilder xml = new StringBuilder("<testsuite>");
        for (int i = 0; i < idsAndOutcomes.length; i += 2) {
            String id = idsAndOutcomes[i];
            String className = id.substring(0, id.indexOf('#'));
            String name = id.substring(id.indexOf('#') + 1);
            xml.append("<testcase classname=\"")
                    .append(className)
                    .append("\" name=\"")
                    .append(name)
                    .append("\">");
            if (idsAndOutcomes[i + 1].equals("FAIL")) {
                xml.append("<failure message=\"boom\"/>");
            }
            xml.append("</testcase>");
        }
        return xml.append("</testsuite>").toString();
    }

    private static SandboxResult output(int exit, String reportXml) {
        String stdout =
                new TestArchive()
                        .add("m/target/surefire-reports/TEST-x.xml", reportXml)
                        .asOutput("build log");
        return new SandboxResult(exit, false, false, false, stdout, "", Duration.ofSeconds(1));
    }

    private static SandboxResult plain(int exit, String stdout) {
        return new SandboxResult(exit, false, false, false, stdout, "", Duration.ofSeconds(1));
    }

    private SandboxRunner runner(Function<SandboxSpec, SandboxResult> behaviour) {
        return new SandboxRunner() {
            @Override
            public String kind() {
                return "fake";
            }

            @Override
            public SandboxResult run(SandboxSpec spec) {
                specs.add(spec);
                return behaviour.apply(spec);
            }
        };
    }

    private boolean runsBaseline(SandboxSpec spec) {
        return spec.mounts().get(0).host().equals(baseline);
    }

    private Verifier verifier(SandboxRunner runner) {
        return new Verifier(new PhaseRunner(runner), new Gates());
    }

    private VerificationRequest request(String diff) {
        return new VerificationRequest(
                baseline, patched, diff, RookConfig.defaults(), cache, "img", 2, false);
    }

    private static String goodDiff() {
        return UnifiedDiff.generate("src/main/java/A.java", SOURCE_BEFORE, SOURCE_AFTER);
    }

    @Test
    void aHealthyPatchPassesAndTheFirstRunWarmsTheCacheWithTheNetwork() {
        String green = junit("a.T#one", "PASS", "a.T#two", "PASS");
        VerificationOutcome outcome =
                verifier(runner(spec -> output(0, green))).verify(request(goodDiff()));
        assertThat(outcome.passed()).as(outcome.summary()).isTrue();
        assertThat(outcome.supported()).isTrue();
        assertThat(outcome.warmedCache()).isTrue();
        assertThat(PhaseRunner.isWarm(cache)).isTrue();
        assertThat(outcome.gates().results())
                .extracting(r -> r.name())
                .contains(Verifier.BASELINE_RAN);
        assertThat(specs).hasSize(2);
        SandboxSpec warm = specs.get(0);
        assertThat(runsBaseline(warm)).isTrue();
        assertThat(warm.network()).isEqualTo(NetworkMode.BRIDGE);
        assertThat(warm.mounts().get(1).readOnly()).isFalse();
        assertThat(warm.command().get(2)).doesNotContain("'-o'");
        SandboxSpec test = specs.get(1);
        assertThat(runsBaseline(test)).isFalse();
        assertThat(test.network()).isEqualTo(NetworkMode.NONE);
        assertThat(test.mounts().get(0).readOnly()).isTrue();
        assertThat(test.mounts().get(1).readOnly()).isTrue();
        assertThat(test.tmpfs())
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.path()).isEqualTo("/work");
                            assertThat(t.sizeMb()).isEqualTo(1024);
                        });
        assertThat(test.command().get(2))
                .contains("'-o'")
                .contains("'-Dmaven.repo.local=/cache/repository'");
        assertThat(test.env()).containsEntry("HOME", "/tmp");
        assertThat(test.maxOutputBytes()).isEqualTo(12 * 1024 * 1024);
    }

    @Test
    void aWarmCacheMeansNoRunGetsTheNetwork() throws IOException {
        Files.createDirectories(cache);
        Files.writeString(cache.resolve(".rook-warm"), "warm");
        String green = junit("a.T#one", "PASS");
        VerificationOutcome outcome =
                verifier(runner(spec -> output(0, green))).verify(request(goodDiff()));
        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.warmedCache()).isFalse();
        assertThat(specs).allMatch(spec -> spec.network() == NetworkMode.NONE);
        assertThat(specs).allMatch(spec -> spec.mounts().get(1).readOnly());
    }

    @Test
    void aNewlyFailingTestRejectsThePatch() {
        VerificationOutcome outcome =
                verifier(
                                runner(
                                        spec ->
                                                runsBaseline(spec)
                                                        ? output(0, junit("a.T#one", "PASS"))
                                                        : output(1, junit("a.T#one", "FAIL"))))
                        .verify(request(goodDiff()));
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.comparison().regressions()).containsExactly("a.T#one");
        assertThat(outcome.flakes().confirmed()).containsExactly("a.T#one");
        assertThat(outcome.gates().failures())
                .extracting(r -> r.name())
                .contains(Gates.NO_NEW_FAILURES);
        assertThat(outcome.summary()).startsWith("rejected:");
        assertThat(
                        specs.stream()
                                .filter(s -> s.command().get(2).contains("-Dtest=a.T#one"))
                                .count())
                .isEqualTo(2);
    }

    @Test
    void aFailureThatPassesOnARerunIsTreatedAsFlaky() {
        int[] patchedRuns = {0};
        VerificationOutcome outcome =
                verifier(
                                runner(
                                        spec -> {
                                            if (runsBaseline(spec)) {
                                                return output(0, junit("a.T#one", "PASS"));
                                            }
                                            patchedRuns[0]++;
                                            return patchedRuns[0] == 1
                                                    ? output(1, junit("a.T#one", "FAIL"))
                                                    : output(0, junit("a.T#one", "PASS"));
                                        }))
                        .verify(request(goodDiff()));
        assertThat(outcome.passed()).as(outcome.summary()).isTrue();
        assertThat(outcome.flakes().flaky()).containsExactly("a.T#one");
    }

    @Test
    void noRerunsHappenWhenTheyAreDisabled() {
        VerificationRequest noReruns =
                new VerificationRequest(
                        baseline,
                        patched,
                        goodDiff(),
                        RookConfig.defaults(),
                        cache,
                        "img",
                        0,
                        false);
        VerificationOutcome outcome =
                verifier(
                                runner(
                                        spec ->
                                                runsBaseline(spec)
                                                        ? output(0, junit("a.T#one", "PASS"))
                                                        : output(1, junit("a.T#one", "FAIL"))))
                        .verify(noReruns);
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.flakes()).isNull();
        assertThat(specs).hasSize(2);
    }

    @Test
    void aBaselineThatCouldNotRunTestsRejectsEvenAGreenPatch() {
        VerificationOutcome outcome =
                verifier(
                                runner(
                                        spec ->
                                                runsBaseline(spec)
                                                        ? plain(1, "[ERROR] COMPILATION ERROR : x")
                                                        : output(0, junit("a.T#one", "PASS"))))
                        .verify(request(goodDiff()));
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.baseline().status()).isEqualTo(RunSummary.Status.COMPILE_FAILED);
        assertThat(outcome.gates().failures())
                .extracting(r -> r.name())
                .containsExactly(Verifier.BASELINE_RAN);
        assertThat(PhaseRunner.isWarm(cache)).isFalse();
    }

    @Test
    void aPatchThatBreaksTheBuildIsRejectedByTheCompileGate() {
        VerificationOutcome outcome =
                verifier(
                                runner(
                                        spec ->
                                                runsBaseline(spec)
                                                        ? output(0, junit("a.T#one", "PASS"))
                                                        : plain(1, "COMPILATION ERROR")))
                        .verify(request(goodDiff()));
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.gates().failures())
                .extracting(r -> r.name())
                .contains(Gates.COMPILES, Gates.TESTS_EXECUTED);
    }

    @Test
    void unsupportedProjectsAreReportedNotGuessed() throws IOException {
        Files.delete(patched.resolve("pom.xml"));
        VerificationOutcome outcome =
                verifier(runner(spec -> plain(0, ""))).verify(request(goodDiff()));
        assertThat(outcome.supported()).isFalse();
        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.summary()).contains("verification is not possible").contains("pom.xml");
        assertThat(specs).isEmpty();
    }

    @Test
    void anUnsafeVerifyCommandStopsBeforeAnythingRuns() {
        RookConfig unsafe = RookConfig.parse("verify:\n  command: mvn test; curl evil.test\n");
        VerificationRequest request =
                new VerificationRequest(
                        baseline, patched, goodDiff(), unsafe, cache, "img", 1, false);
        VerificationOutcome outcome = verifier(runner(spec -> plain(0, ""))).verify(request);
        assertThat(outcome.supported()).isFalse();
        assertThat(outcome.unsupportedReason()).contains("plain arguments");
        assertThat(specs).isEmpty();
    }

    @Test
    void rerunFiltersNameClassesAndMethodsWithoutParameterSuffixes() {
        assertThat(Verifier.filter(Set.of("a.T#m(String)[1]"))).isEqualTo("a.T#m");
        assertThat(Verifier.filter(new TreeSet<>(Set.of("a.T#m", "b.U#x", "c.V"))))
                .isEqualTo("a.T#m,b.U#x,c.V");
        assertThat(Verifier.filter(Set.of("a.T#m[2]", "a.T#m(int)"))).isEqualTo("a.T#m");
    }

    @Test
    void theAgentPortWarmsFirstThenRunsOfflineWithAScope() throws IOException {
        String green = junit("a.T#one", "PASS", "a.T#two", "PASS");
        DockerSandboxPort port =
                new DockerSandboxPort(
                        new PhaseRunner(runner(spec -> output(0, green))),
                        RookConfig.defaults().verify(),
                        cache,
                        "img");
        TestRun first = port.run(patched, "all");
        assertThat(first.passed()).isTrue();
        assertThat(first.executed()).isEqualTo(2);
        assertThat(specs.get(0).network()).isEqualTo(NetworkMode.BRIDGE);
        TestRun second = port.run(patched, "a.T");
        assertThat(second.passed()).isTrue();
        assertThat(specs.get(1).network()).isEqualTo(NetworkMode.NONE);
        assertThat(specs.get(1).command().get(2)).contains("'-Dtest=a.T'");
        port.run(patched, "all");
        assertThat(specs.get(2).command().get(2)).doesNotContain("-Dtest=");
    }

    @Test
    void theAgentPortReportsBuildsThatFailOrCannotRun() throws IOException {
        DockerSandboxPort failing =
                new DockerSandboxPort(
                        new PhaseRunner(runner(spec -> plain(1, "[ERROR] COMPILATION ERROR"))),
                        RookConfig.defaults().verify(),
                        cache,
                        "img");
        TestRun broken = failing.run(patched, "all");
        assertThat(broken.compiled()).isFalse();
        assertThat(broken.passed()).isFalse();
        Files.delete(patched.resolve("pom.xml"));
        TestRun unsupported = failing.run(patched, "all");
        assertThat(unsupported.describe()).contains("verification is not possible");
        RookConfig unsafe = RookConfig.parse("verify:\n  command: mvn test && id\n");
        TestRun refused =
                new DockerSandboxPort(
                                new PhaseRunner(runner(spec -> plain(0, ""))),
                                unsafe.verify(),
                                cache,
                                "img")
                        .run(patched, "all");
        assertThat(refused.describe()).contains("verification is not possible");
    }
}
