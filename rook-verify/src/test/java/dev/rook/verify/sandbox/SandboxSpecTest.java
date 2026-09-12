package dev.rook.verify.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxSpecTest {

    private static SandboxSpec base() {
        return SandboxSpec.of("img", "/work", List.of("echo", "hi"));
    }

    @Test
    void defaultsAreConservative() {
        SandboxSpec spec = base();
        assertThat(spec.network()).isEqualTo(NetworkMode.NONE);
        assertThat(spec.timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(spec.memoryMb()).isEqualTo(1024);
        assertThat(spec.pids()).isEqualTo(256);
        assertThat(spec.mounts()).isEmpty();
    }

    @Test
    void withersReplaceOnlyTheirOwnField() {
        SandboxSpec changed =
                base().withTimeout(Duration.ofSeconds(9))
                        .withLimits(2, 512, 64)
                        .withNetwork(NetworkMode.BRIDGE)
                        .withEnv(Map.of("A", "1"))
                        .withLabels(Map.of("rook.job", "x"))
                        .withMounts(List.of(Mount.readOnly(Path.of("a"), "/src")))
                        .withMaxOutputBytes(2048);
        assertThat(changed.timeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(changed.cpus()).isEqualTo(2);
        assertThat(changed.memoryMb()).isEqualTo(512);
        assertThat(changed.pids()).isEqualTo(64);
        assertThat(changed.network()).isEqualTo(NetworkMode.BRIDGE);
        assertThat(changed.env()).containsEntry("A", "1");
        assertThat(changed.labels()).containsEntry("rook.job", "x");
        assertThat(changed.mounts()).hasSize(1);
        assertThat(changed.maxOutputBytes()).isEqualTo(2048);
        assertThat(changed.image()).isEqualTo("img");
        assertThat(changed.command()).containsExactly("echo", "hi");
    }

    @Test
    void nonsenseSpecsAreRejected() {
        assertThatThrownBy(() -> SandboxSpec.of(" ", "/w", List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SandboxSpec.of("img", "/w", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().withTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().withLimits(0, 512, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().withLimits(1, 8, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().withLimits(1, 512, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().withMaxOutputBytes(10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().withEnv(Map.of("BAD-NAME", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().withLabels(Map.of("Bad Key", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tmpfsAreasAreValidated() {
        assertThat(base().withTmpfs(List.of(new SandboxSpec.Tmpfs("/work", 64))).tmpfs())
                .hasSize(1);
        assertThat(base().tmpfs()).isEmpty();
        assertThatThrownBy(() -> new SandboxSpec.Tmpfs("work", 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxSpec.Tmpfs("/a:b", 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxSpec.Tmpfs("/a/../b", 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SandboxSpec.Tmpfs("/work", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mountsNeedAbsoluteSafeContainerPaths() {
        assertThatThrownBy(() -> Mount.readOnly(Path.of("a"), "relative"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Mount.readOnly(Path.of("a"), "/a:/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Mount.readOnly(Path.of("a"), "/a/../b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Mount.readWrite(Path.of("a"), "/work").readOnly()).isFalse();
        assertThat(Mount.readOnly(Path.of("a"), "/work").readOnly()).isTrue();
    }

    @Test
    void resultsDescribeSuccessAndCombineOutput() {
        SandboxResult ok = new SandboxResult(0, false, false, false, "out", "err", Duration.ZERO);
        assertThat(ok.succeeded()).isTrue();
        assertThat(ok.combinedOutput()).isEqualTo("out\nerr");
        assertThat(
                        new SandboxResult(0, false, false, false, "out", "", Duration.ZERO)
                                .combinedOutput())
                .isEqualTo("out");
        assertThat(
                        new SandboxResult(0, false, false, false, "", "err", Duration.ZERO)
                                .combinedOutput())
                .isEqualTo("err");
        assertThat(new SandboxResult(0, true, false, false, null, null, Duration.ZERO).succeeded())
                .isFalse();
        assertThat(new SandboxResult(0, false, true, false, "", "", Duration.ZERO).succeeded())
                .isFalse();
        assertThat(new SandboxResult(1, false, false, false, "", "", Duration.ZERO).succeeded())
                .isFalse();
    }
}
