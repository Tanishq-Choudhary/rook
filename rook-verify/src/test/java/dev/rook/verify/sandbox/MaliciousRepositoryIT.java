package dev.rook.verify.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.agent.workspace.WorkspaceViolation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaliciousRepositoryIT {

    private static final String IMAGE = DockerSandboxRunnerIT.IMAGE;

    @TempDir Path temp;

    private DockerSandboxRunner runner =
            new DockerSandboxRunner(DockerConfig.standard(), Clock.systemUTC());

    @BeforeEach
    void requireDocker() {
        assumeTrue(runner.available(), "docker is not available");
    }

    private static SandboxSpec bash(String script) {
        return SandboxSpec.of(IMAGE, "/tmp", List.of("bash", "-c", script))
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

    private void assertHostStillHealthy() {
        SandboxResult after = runner.run(bash("echo alive"));
        assertThat(after.succeeded()).as(after.combinedOutput()).isTrue();
        assertThat(after.stdout().strip()).isEqualTo("alive");
    }

    @Test
    void aForkBombIsHeldBackByTheProcessLimit() throws Exception {
        String id = UUID.randomUUID().toString();
        SandboxResult result =
                runner.run(
                        bash(":(){ :|:& };: ; sleep 20")
                                .withLimits(1, 256, 64)
                                .withTimeout(Duration.ofSeconds(15))
                                .withLabels(Map.of("rook.test", id)));
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(60));
        assertThat(containersWith("rook.test=" + id)).isEmpty();
        assertHostStillHealthy();
    }

    @Test
    void networkEgressAndNameResolutionAreImpossible() {
        SandboxResult result =
                runner.run(
                        bash(
                                "(exec 3<>/dev/tcp/1.1.1.1/443) 2>/dev/null && echo OPEN || echo CLOSED;"
                                        + " getent hosts example.com >/dev/null 2>&1 && echo DNS || echo NODNS;"
                                        + " ls /sys/class/net | tr '\\n' ' '"));
        assertThat(result.stdout()).contains("CLOSED").contains("NODNS");
        assertThat(
                        result.stdout()
                                .replace("lo", "")
                                .replace("CLOSED", "")
                                .replace("NODNS", "")
                                .strip())
                .isEmpty();
    }

    @Test
    void aMemoryBombIsKilledWithoutHurtingTheHost() {
        SandboxResult result =
                runner.run(
                        bash("v=$(head -c 500000000 /dev/zero | tr '\\0' a); echo ${#v}")
                                .withLimits(1, 64, 128));
        assertThat(result.oomKilled()).isTrue();
        assertHostStillHealthy();
    }

    @Test
    void fillingTheRootFilesystemOrTmpIsRefused() {
        SandboxResult result =
                runner.run(
                        bash("dd if=/dev/zero of=/etc/big bs=1M count=10 2>/dev/null && echo ROOT_WRITTEN"
                                        + " || echo ROOT_REFUSED;"
                                        + " dd if=/dev/zero of=/tmp/big bs=1M count=600 2>&1 | grep -m1 'No space'")
                                .withLimits(1, 1024, 128));
        assertThat(result.stdout()).contains("ROOT_REFUSED").doesNotContain("ROOT_WRITTEN");
        assertThat(result.combinedOutput()).contains("No space left on device");
    }

    @Test
    void aWritableMountThatGrowsPastTheQuotaGetsTheContainerKilled() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work"));
        DockerSandboxRunner small =
                new DockerSandboxRunner(
                        DockerConfig.standard().withWorkspaceLimits(5L * 1024 * 1024, 100_000),
                        Clock.systemUTC());
        SandboxResult result =
                small.run(
                        bash("dd if=/dev/zero of=/work/fill bs=1M count=2000 2>/dev/null; sleep 30")
                                .withMounts(List.of(Mount.readWrite(work, "/work")))
                                .withTimeout(Duration.ofSeconds(90)));
        assertThat(result.diskExceeded()).isTrue();
        assertThat(result.succeeded()).isFalse();
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(28));
    }

    @Test
    void anInodeBombInAWritableMountIsStopped() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work"));
        DockerSandboxRunner small =
                new DockerSandboxRunner(
                        DockerConfig.standard().withWorkspaceLimits(1L << 30, 300),
                        Clock.systemUTC());
        SandboxResult result =
                small.run(
                        bash("for i in $(seq 1 100000); do : > /work/f$i; done; sleep 30")
                                .withMounts(List.of(Mount.readWrite(work, "/work")))
                                .withTimeout(Duration.ofSeconds(120)));
        assertThat(result.diskExceeded()).isTrue();
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(100));
    }

    @Test
    void aBusyLoopIsKilledAtTheTimeout() throws Exception {
        String id = UUID.randomUUID().toString();
        SandboxResult result =
                runner.run(
                        bash("while :; do :; done")
                                .withTimeout(Duration.ofSeconds(3))
                                .withLabels(Map.of("rook.test", id)));
        assertThat(result.timedOut()).isTrue();
        assertThat(containersWith("rook.test=" + id)).isEmpty();
    }

    @Test
    void endlessOutputIsBoundedInMemory() {
        SandboxResult result =
                runner.run(bash("yes").withTimeout(Duration.ofSeconds(4)).withMaxOutputBytes(4096));
        assertThat(result.timedOut()).isTrue();
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.stdout().length()).isLessThanOrEqualTo(2048);
    }

    @Test
    void theHostEnvironmentIsNotVisibleInsideTheContainer() {
        Set<String> imageDefaults =
                Set.of(
                        "PATH",
                        "HOME",
                        "HOSTNAME",
                        "JAVA_HOME",
                        "MAVEN_HOME",
                        "MAVEN_CONFIG",
                        "LANG",
                        "TERM",
                        "PWD",
                        "SHLVL",
                        "_",
                        "LC_ALL");
        SandboxResult result = runner.run(bash("printenv"));
        List<String> insideNames =
                result.stdout()
                        .lines()
                        .map(l -> l.substring(0, Math.max(0, l.indexOf('='))))
                        .toList();
        for (String name : System.getenv().keySet()) {
            if (!imageDefaults.contains(name) && name.length() >= 3) {
                assertThat(insideNames).as(name).doesNotContain(name);
            }
        }
    }

    @Test
    void writesOutsideTheMountedWorkspaceNeverReachTheHost() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work"));
        SandboxResult result =
                runner.run(
                        bash("cd /work && (echo x > ../escape.txt) 2>/dev/null && echo UP_WRITTEN || echo UP_REFUSED;"
                                        + " (echo x > /escape.txt) 2>/dev/null && echo ROOT_WRITTEN || echo ROOT_REFUSED;"
                                        + " echo inside > /work/ok.txt")
                                .withMounts(List.of(Mount.readWrite(work, "/work"))));
        assertThat(result.stdout()).contains("UP_REFUSED").contains("ROOT_REFUSED");
        assertThat(Files.exists(temp.resolve("escape.txt"))).isFalse();
        assertThat(Files.readString(work.resolve("ok.txt")).strip()).isEqualTo("inside");
    }

    @Test
    void unmountedHostFilesCannotBeRead() throws IOException {
        Files.writeString(temp.resolve("secret.txt"), "TOPSECRETVALUE");
        Path work = Files.createDirectories(temp.resolve("work"));
        SandboxResult result =
                runner.run(
                        bash("grep -rl TOPSECRETVALUE /work /tmp /home /mnt 2>/dev/null; echo done")
                                .withMounts(List.of(Mount.readOnly(work, "/work"))));
        assertThat(result.stdout().strip()).isEqualTo("done");
    }

    @Test
    void capabilitiesAreDroppedAndTheDockerSocketIsAbsent() {
        SandboxResult result =
                runner.run(
                        bash(
                                "grep CapBnd /proc/self/status; ls /var/run/docker.sock 2>/dev/null"
                                        + " && echo SOCKET || echo NOSOCKET"));
        assertThat(result.stdout()).contains("0000000000000000").contains("NOSOCKET");
    }

    @Test
    void aSymlinkPlantedInTheWorkspaceCannotLeadTheHostOutOfIt() throws IOException {
        Path work = Files.createDirectories(temp.resolve("work"));
        Files.writeString(temp.resolve("outside.txt"), "private");
        runner.run(
                bash("ln -s ../outside.txt /work/escape && ln -s /etc/hostname /work/abs")
                        .withMounts(List.of(Mount.readWrite(work, "/work"))));
        assumeTrue(
                Files.isSymbolicLink(work.resolve("escape")),
                "symbolic links are not available here");
        Workspace workspace = Workspace.over(work, WorkspacePolicy.standard());
        for (String link : List.of("escape", "abs")) {
            try {
                workspace.read(link);
                throw new AssertionError("the symlink " + link + " was followed");
            } catch (WorkspaceViolation expected) {
                assertThat(expected.code())
                        .isIn(
                                WorkspaceViolation.Code.OUTSIDE_ROOT,
                                WorkspaceViolation.Code.NOT_FOUND);
            }
        }
        assertThat(workspace.list("", 100)).doesNotContain("escape", "abs");
    }
}
