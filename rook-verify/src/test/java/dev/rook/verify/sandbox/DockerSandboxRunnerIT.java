package dev.rook.verify.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxRunnerIT {

    static final String IMAGE = "maven:3.9-eclipse-temurin-21";

    @TempDir Path temp;

    private final DockerSandboxRunner runner =
            new DockerSandboxRunner(DockerConfig.standard(), Clock.systemUTC());

    @BeforeEach
    void requireDocker() {
        assumeTrue(runner.available(), "docker is not available");
    }

    private SandboxSpec shell(String script) {
        return SandboxSpec.of(IMAGE, "/tmp", List.of("sh", "-c", script))
                .withTimeout(Duration.ofSeconds(60))
                .withLimits(1, 256, 128);
    }

    private static List<String> containersWith(String label)
            throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder("docker", "ps", "--all", "--quiet", "--filter", "label=" + label)
                        .redirectErrorStream(true)
                        .start();
        String out = new String(process.getInputStream().readAllBytes()).strip();
        process.waitFor();
        return out.isEmpty() ? List.of() : List.of(out.split("\\R"));
    }

    @Test
    void outputAndExitCodeComeBackFromTheContainer() {
        SandboxResult ok = runner.run(shell("echo hello; echo problem 1>&2"));
        assertThat(ok.succeeded()).isTrue();
        assertThat(ok.stdout().strip()).isEqualTo("hello");
        assertThat(ok.stderr().strip()).isEqualTo("problem");
        SandboxResult failed = runner.run(shell("exit 7"));
        assertThat(failed.exitCode()).isEqualTo(7);
        assertThat(failed.succeeded()).isFalse();
        assertThat(runner.kind()).isEqualTo("docker");
    }

    @Test
    void theContainerIsRemovedAfterAnyRun() throws Exception {
        String id = UUID.randomUUID().toString();
        runner.run(shell("echo x").withLabels(Map.of("rook.test", id)));
        runner.run(shell("exit 3").withLabels(Map.of("rook.test", id)));
        assertThat(containersWith("rook.test=" + id)).isEmpty();
    }

    @Test
    void aRunThatOutlastsItsTimeoutIsKilledAndCleanedUp() throws Exception {
        String id = UUID.randomUUID().toString();
        SandboxResult result =
                runner.run(
                        shell("sleep 120")
                                .withTimeout(Duration.ofSeconds(3))
                                .withLabels(Map.of("rook.test", id)));
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(40));
        assertThat(containersWith("rook.test=" + id)).isEmpty();
    }

    @Test
    void mountsAreWritableOnlyWhenRequested() throws IOException {
        Path rw = Files.createDirectories(temp.resolve("rw"));
        Path ro = Files.createDirectories(temp.resolve("ro"));
        Files.writeString(ro.resolve("a.txt"), "from host");
        SandboxSpec spec =
                shell("cat /ro/a.txt && echo written > /rw/out.txt && ! touch /ro/b.txt")
                        .withMounts(List.of(Mount.readWrite(rw, "/rw"), Mount.readOnly(ro, "/ro")));
        SandboxResult result = runner.run(spec);
        assertThat(result.succeeded()).as(result.combinedOutput()).isTrue();
        assertThat(result.stdout()).contains("from host");
        assertThat(Files.readString(rw.resolve("out.txt")).strip()).isEqualTo("written");
        assertThat(Files.exists(ro.resolve("b.txt"))).isFalse();
    }

    @Test
    void theRootFilesystemIsReadOnlyButTmpIsWritable() {
        SandboxResult result =
                runner.run(shell("! touch /etc/rook-test && touch /tmp/ok && echo fine"));
        assertThat(result.succeeded()).as(result.combinedOutput()).isTrue();
        assertThat(result.stdout().strip()).isEqualTo("fine");
    }

    @Test
    void processesRunAsAnUnprivilegedUser() {
        assertThat(runner.run(shell("id -u")).stdout().strip()).isEqualTo("1000");
        assertThat(runner.run(shell("grep CapEff /proc/self/status")).stdout())
                .contains("0000000000000000");
    }

    @Test
    void thereIsNoNetworkByDefaultAndEnvironmentIsExplicit() {
        SandboxResult network =
                runner.run(
                        shell(
                                "bash -c 'exec 3<>/dev/tcp/1.1.1.1/53' && echo reachable || echo blocked"));
        assertThat(network.stdout().strip()).isEqualTo("blocked");
        SandboxResult env =
                runner.run(shell("echo $ROOK_FLAG").withEnv(Map.of("ROOK_FLAG", "yes")));
        assertThat(env.stdout().strip()).isEqualTo("yes");
    }

    @Test
    void exceedingTheMemoryLimitIsReportedAsOomKilled() {
        SandboxResult result =
                runner.run(
                        SandboxSpec.of(
                                        IMAGE,
                                        "/tmp",
                                        List.of(
                                                "bash",
                                                "-c",
                                                "v=$(head -c 400000000 /dev/zero | tr '\\0' a); echo ${#v}"))
                                .withLimits(1, 64, 128)
                                .withTimeout(Duration.ofSeconds(60)));
        assertThat(result.oomKilled()).isTrue();
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void hugeOutputIsCutAndFlagged() {
        SandboxResult result =
                runner.run(shell("yes | head -c 5000000; echo END").withMaxOutputBytes(4096));
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.stdout().length()).isLessThanOrEqualTo(2048);
    }

    @Test
    void anUnknownImageFailsWithoutPulling() {
        SandboxResult result =
                runner.run(
                        SandboxSpec.of("rook-test/no-such-image:0", "/tmp", List.of("true"))
                                .withTimeout(Duration.ofSeconds(30)));
        assertThat(result.succeeded()).isFalse();
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void aMissingDockerBinaryIsReportedClearly() {
        DockerSandboxRunner broken =
                new DockerSandboxRunner(
                        DockerConfig.standard().withBinary("no-such-docker-binary"),
                        Clock.systemUTC());
        assertThat(broken.available()).isFalse();
        assertThatThrownBy(() -> broken.run(shell("echo x"))).isInstanceOf(SandboxException.class);
    }

    @Test
    void theReaperRemovesOldManagedContainers() throws Exception {
        String name = "rook-test-" + UUID.randomUUID().toString().substring(0, 8);
        Process started =
                new ProcessBuilder(
                                "docker",
                                "run",
                                "--detach",
                                "--name",
                                name,
                                "--label",
                                "rook.managed=true",
                                "--label",
                                "rook.started=1000",
                                IMAGE,
                                "sleep",
                                "120")
                        .redirectErrorStream(true)
                        .start();
        started.getInputStream().readAllBytes();
        assertThat(started.waitFor()).isZero();
        DockerReaper reaper = new DockerReaper(DockerConfig.standard(), Clock.systemUTC());
        assertThat(reaper.reap(Duration.ofMinutes(1))).isGreaterThanOrEqualTo(1);
        Process check =
                new ProcessBuilder("docker", "ps", "--all", "--quiet", "--filter", "name=" + name)
                        .redirectErrorStream(true)
                        .start();
        assertThat(new String(check.getInputStream().readAllBytes()).strip()).isEmpty();
    }
}
