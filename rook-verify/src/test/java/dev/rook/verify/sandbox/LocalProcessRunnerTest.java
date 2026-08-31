package dev.rook.verify.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProcessRunnerTest {

    @TempDir Path temp;

    private final LocalProcessRunner runner = LocalProcessRunner.create(true);

    private SandboxSpec spec(String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ChildProcessMain.class.getName());
        command.addAll(List.of(args));
        return SandboxSpec.of("local", temp.toString(), command)
                .withTimeout(Duration.ofSeconds(30));
    }

    @Test
    void theRunnerRefusesToStartUnlessUnsafeLocalExecutionIsAllowed() {
        assertThatThrownBy(() -> LocalProcessRunner.create(false))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("allow-unsafe-local");
        assertThat(runner.kind()).isEqualTo("local");
    }

    @Test
    void outputAndExitCodeAreCaptured() {
        SandboxResult ok = runner.run(spec("print", "hello"));
        assertThat(ok.succeeded()).isTrue();
        assertThat(ok.stdout().strip()).isEqualTo("hello");
        assertThat(ok.duration()).isPositive();
        SandboxResult failed = runner.run(spec("exit", "3"));
        assertThat(failed.exitCode()).isEqualTo(3);
        assertThat(failed.succeeded()).isFalse();
        SandboxResult err = runner.run(spec("err", "oops"));
        assertThat(err.stderr().strip()).isEqualTo("oops");
        assertThat(err.combinedOutput()).contains("oops");
    }

    @Test
    void theProcessRunsInTheRequestedDirectory() throws IOException {
        Path dir = Files.createDirectories(temp.resolve("work"));
        SandboxSpec inDir =
                new SandboxSpec(
                        "local",
                        dir.toString(),
                        spec("cwd").command(),
                        Map.of(),
                        Duration.ofSeconds(30),
                        1,
                        256,
                        64,
                        NetworkMode.NONE,
                        List.of(),
                        Map.of(),
                        4096);
        assertThat(Path.of(runner.run(inDir).stdout().strip()).toRealPath())
                .isEqualTo(dir.toRealPath());
    }

    @Test
    void theEnvironmentIsScrubbedToTheAllowList() {
        SandboxSpec leaking = spec("env", "ROOK_TEST_SECRET");
        assertThat(runner.run(leaking.withEnv(Map.of())).stdout().strip()).isEqualTo("<unset>");
        assertThat(
                        runner.run(leaking.withEnv(Map.of("ROOK_TEST_SECRET", "given")))
                                .stdout()
                                .strip())
                .isEqualTo("given");
        String path = System.getenv("PATH");
        assertThat(runner.run(spec("env", "PATH")).stdout().strip()).isEqualTo(path);
    }

    @Test
    void variablesOutsideTheAllowListNeverReachTheChild() {
        String existing =
                System.getenv().keySet().stream()
                        .filter(
                                k ->
                                        !k.equalsIgnoreCase("PATH")
                                                && !LocalProcessRunner.DEFAULT_INHERITED.contains(
                                                        k))
                        .findFirst()
                        .orElse(null);
        if (existing == null) {
            return;
        }
        assertThat(runner.run(spec("env", existing)).stdout().strip()).isEqualTo("<unset>");
        LocalProcessRunner widened =
                LocalProcessRunner.create(
                        true, Set.of("PATH", "JAVA_HOME", "SystemRoot", existing));
        assertThat(widened.run(spec("env", existing)).stdout().strip()).isNotEqualTo("<unset>");
    }

    @Test
    void standardInputIsClosedSoReadersSeeEndOfFile() {
        assertThat(runner.run(spec("stdin")).stdout().strip()).isEqualTo("read 0");
    }

    @Test
    void aProcessThatRunsTooLongIsKilledAndReportedAsTimedOut() {
        SandboxResult result =
                runner.run(spec("sleep", "60000").withTimeout(Duration.ofMillis(800)));
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.duration()).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void theWholeProcessTreeDiesOnTimeout() throws IOException {
        Path pidFile = temp.resolve("grandchild.pid");
        SandboxResult result =
                runner.run(
                        spec("grandchild", pidFile.toString()).withTimeout(Duration.ofSeconds(4)));
        assertThat(result.timedOut()).isTrue();
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        await().atMost(Duration.ofSeconds(10))
                .until(
                        () ->
                                ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                                        == false);
    }

    @Test
    void hugeOutputIsCutToTheTailAndFlagged() {
        SandboxResult result = runner.run(spec("spam", "400000").withMaxOutputBytes(4096));
        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.stdout().length()).isLessThanOrEqualTo(2048);
        assertThat(result.stdout()).contains("END");
    }

    @Test
    void smallOutputIsNotFlaggedAsTruncated() {
        assertThat(runner.run(spec("print", "short")).outputTruncated()).isFalse();
    }

    @Test
    void missingExecutablesAndDirectoriesAreReported() {
        SandboxSpec missing =
                SandboxSpec.of("local", temp.toString(), List.of("definitely-not-a-program-xyz"));
        assertThatThrownBy(() -> runner.run(missing)).isInstanceOf(SandboxException.class);
        SandboxSpec noDir =
                SandboxSpec.of("local", temp.resolve("absent").toString(), List.of("x"));
        assertThatThrownBy(() -> runner.run(noDir))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("does not exist");
    }
}
