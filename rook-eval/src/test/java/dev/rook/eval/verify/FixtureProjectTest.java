package dev.rook.eval.verify;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.generate.FixtureGenerator;
import dev.rook.verify.sandbox.SandboxResult;
import dev.rook.verify.sandbox.SandboxRunner;
import dev.rook.verify.sandbox.SandboxSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureProjectTest {

    @TempDir static Path shared;

    static Fixture rename;
    static Fixture move;
    static Fixture noop;

    @BeforeAll
    static void generate() {
        List<Fixture> all = FixtureGenerator.generate(shared.resolve("fixtures"));
        rename = byId(all, "method-rename-pay-field");
        move = byId(all, "class-move-mail-local");
        noop = byId(all, "unrelated-update-geo-field");
    }

    private static Fixture byId(List<Fixture> all, String id) {
        return all.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void assemblingPlacesTheChosenSdkAndTheIndependentTests(@TempDir Path temp) throws IOException {
        Path before =
                FixtureProject.assemble(
                        rename, rename.repo(), SdkVersion.BEFORE, temp.resolve("b"));
        Path after =
                FixtureProject.assemble(rename, rename.repo(), SdkVersion.AFTER, temp.resolve("a"));
        assertThat(Files.readString(before.resolve("src/main/java/paylib/PayClient.java")))
                .contains("String charge(");
        assertThat(Files.readString(after.resolve("src/main/java/paylib/PayClient.java")))
                .contains("String createCharge(")
                .doesNotContain("String charge(");
        assertThat(before.resolve("src/test/java/app/ServiceContractTest.java")).exists();
        assertThat(before.resolve("src/test/java/app/ServiceTest.java")).exists();
        assertThat(before.resolve("pom.xml")).exists();
    }

    @Test
    void aMovedClassLandsInItsNewPackageOnly(@TempDir Path temp) {
        Path after =
                FixtureProject.assemble(move, move.repo(), SdkVersion.AFTER, temp.resolve("a"));
        assertThat(after.resolve("src/main/java/maillib/client/MailClient.java")).exists();
        assertThat(after.resolve("src/main/java/maillib/MailClient.java")).doesNotExist();
    }

    @Test
    void assemblingTwiceReplacesTheOldContent(@TempDir Path temp) throws IOException {
        Path target = temp.resolve("p");
        FixtureProject.assemble(rename, rename.repo(), SdkVersion.BEFORE, target);
        Files.writeString(target.resolve("stale.txt"), "x");
        FixtureProject.assemble(rename, rename.repo(), SdkVersion.AFTER, target);
        assertThat(target.resolve("stale.txt")).doesNotExist();
    }

    @Test
    void goldOverlaysOnlyTheChangedFiles(@TempDir Path temp) {
        Path gold = FixtureProject.withGold(rename, temp.resolve("g"));
        Map<String, String> repo = Fixture.readTree(rename.repo());
        Map<String, String> merged = Fixture.readTree(gold);
        assertThat(merged.keySet()).isEqualTo(repo.keySet());
        rename.goldFiles()
                .forEach((path, content) -> assertThat(merged).containsEntry(path, content));
        assertThat(FixtureProject.withGold(noop, temp.resolve("n")))
                .isDirectoryContaining("glob:**/pom.xml");
    }

    @Test
    void independentTestClassesAreNamedAsJunitReportsName() {
        assertThat(FixtureProject.independentClasses(rename))
                .containsExactly("app.ServiceContractTest");
    }

    @Test
    void verdictsAcceptOnlyWhenBothSuitesRanAndPassed() {
        Verdict good = new Verdict(true, 2, List.of(), 3, List.of(), 5, "");
        assertThat(good.accepted()).isTrue();
        assertThat(new Verdict(true, 2, List.of("a#b"), 3, List.of(), 5, null).accepted())
                .isFalse();
        assertThat(new Verdict(true, 2, List.of(), 3, List.of("c#d"), 5, "").accepted()).isFalse();
        assertThat(new Verdict(true, 0, List.of(), 3, List.of(), 5, "").accepted()).isFalse();
        assertThat(new Verdict(true, 1, List.of(), 0, List.of(), 5, "").accepted()).isFalse();
        assertThat(Verdict.notCompiling(9, "boom").accepted()).isFalse();
        assertThat(Verdict.notCompiling(9, null).detail()).isEmpty();
    }

    @Test
    void theMavenVerifierSplitsResultsBetweenVisibleAndIndependentTests(@TempDir Path temp) {
        SandboxRunner fake =
                new SandboxRunner() {
                    @Override
                    public String kind() {
                        return "fake";
                    }

                    @Override
                    public SandboxResult run(SandboxSpec spec) {
                        report(
                                Path.of(spec.workdir()),
                                """
                                <testsuite name="app.ServiceTest">
                                  <testcase classname="app.ServiceTest" name="a" time="0.1"/>
                                  <testcase classname="app.ServiceTest" name="b" time="0.1">
                                    <failure message="boom"/>
                                  </testcase>
                                  <testcase classname="app.ServiceTest" name="skipped" time="0">
                                    <skipped/>
                                  </testcase>
                                </testsuite>
                                """,
                                "ServiceTest");
                        report(
                                Path.of(spec.workdir()),
                                """
                                <testsuite name="app.ServiceContractTest">
                                  <testcase classname="app.ServiceContractTest" name="c" time="0.1"/>
                                </testsuite>
                                """,
                                "ServiceContractTest");
                        return new SandboxResult(
                                1, false, false, false, "", "", Duration.ofMillis(7));
                    }
                };
        LocalMavenVerifier verifier =
                new LocalMavenVerifier(fake, temp.resolve("scratch"), Duration.ofSeconds(5));
        Verdict verdict = verifier.verify(rename, rename.repo(), SdkVersion.AFTER);
        assertThat(verifier.name()).isEqualTo("local-maven");
        assertThat(verdict.compiled()).isTrue();
        assertThat(verdict.visibleRun()).isEqualTo(2);
        assertThat(verdict.visibleFailures()).containsExactly("app.ServiceTest#b");
        assertThat(verdict.independentRun()).isEqualTo(1);
        assertThat(verdict.independentFailures()).isEmpty();
        assertThat(verdict.millis()).isEqualTo(7);
        assertThat(verdict.accepted()).isFalse();
    }

    @Test
    void noReportsMeansTheBuildDidNotGetToTheTests(@TempDir Path temp) {
        SandboxRunner failing =
                new SandboxRunner() {
                    @Override
                    public String kind() {
                        return "fake";
                    }

                    @Override
                    public SandboxResult run(SandboxSpec spec) {
                        return new SandboxResult(
                                1,
                                false,
                                false,
                                false,
                                "[ERROR] Service.java:[10,5] cannot find symbol\n[INFO] done",
                                "",
                                Duration.ofMillis(3));
                    }
                };
        Verdict verdict =
                new LocalMavenVerifier(failing, temp.resolve("s"), Duration.ofSeconds(5))
                        .verify(rename, rename.repo(), SdkVersion.AFTER);
        assertThat(verdict.compiled()).isFalse();
        assertThat(verdict.detail()).contains("cannot find symbol").doesNotContain("done");
    }

    private static void report(Path project, String xml, String name) {
        try {
            Path directory = project.resolve("target/surefire-reports");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("TEST-app." + name + ".xml"), xml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
