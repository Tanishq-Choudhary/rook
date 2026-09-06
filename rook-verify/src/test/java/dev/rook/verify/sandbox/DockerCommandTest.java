package dev.rook.verify.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerCommandTest {

    private static final DockerConfig CONFIG = DockerConfig.standard();

    private static SandboxSpec spec() {
        return SandboxSpec.of("maven:3", "/work", List.of("mvn", "-B", "test"))
                .withEnv(Map.of("HOME", "/tmp", "MAVEN_OPTS", "-Xmx512m"))
                .withLabels(Map.of("rook.job", "j1"))
                .withMounts(
                        List.of(
                                Mount.readWrite(Path.of("workspace").toAbsolutePath(), "/work"),
                                Mount.readOnly(Path.of("cache").toAbsolutePath(), "/cache")));
    }

    private static List<String> build() {
        return DockerCommand.run(spec(), "rook-abc", CONFIG, 1_700_000_000L);
    }

    private static String after(List<String> args, String flag) {
        return args.get(args.indexOf(flag) + 1);
    }

    @Test
    void theCommandStartsWithTheBinaryAndEndsWithImageAndCommand() {
        List<String> args = build();
        assertThat(args.get(0)).isEqualTo("docker");
        assertThat(args.get(1)).isEqualTo("run");
        assertThat(args.subList(args.size() - 4, args.size()))
                .containsExactly("maven:3", "mvn", "-B", "test");
    }

    @Test
    void isolationFlagsAreAlwaysPresent() {
        List<String> args = build();
        assertThat(args).contains("--read-only", "--init");
        assertThat(after(args, "--network")).isEqualTo("none");
        assertThat(after(args, "--cap-drop")).isEqualTo("ALL");
        assertThat(after(args, "--security-opt")).isEqualTo("no-new-privileges");
        assertThat(after(args, "--user")).isEqualTo("1000:1000");
        assertThat(after(args, "--pull")).isEqualTo("never");
        assertThat(after(args, "--tmpfs")).isEqualTo("/tmp:rw,nosuid,size=256m");
        assertThat(args).contains("nofile=4096:4096", "core=0");
    }

    @Test
    void resourceLimitsComeFromTheSpecAndSwapIsDisabled() {
        List<String> args = DockerCommand.run(spec().withLimits(1.5, 768, 99), "n", CONFIG, 1);
        assertThat(after(args, "--memory")).isEqualTo("768m");
        assertThat(after(args, "--memory-swap")).isEqualTo("768m");
        assertThat(after(args, "--cpus")).isEqualTo("1.5");
        assertThat(after(args, "--pids-limit")).isEqualTo("99");
        assertThat(after(DockerCommand.run(spec(), "n", CONFIG, 1), "--cpus")).isEqualTo("1");
    }

    @Test
    void labelsIncludeTheManagedMarkerAndStartTime() {
        List<String> args = build();
        assertThat(args).contains("rook.job=j1", "rook.managed=true", "rook.started=1700000000");
        assertThat(after(args, "--name")).isEqualTo("rook-abc");
    }

    @Test
    void environmentAndMountsAreRenderedWithoutShellParsing() {
        List<String> args = build();
        assertThat(args).contains("HOME=/tmp", "MAVEN_OPTS=-Xmx512m");
        assertThat(args.stream().filter(a -> a.startsWith("type=bind")).toList())
                .hasSize(2)
                .anyMatch(a -> a.endsWith("target=/work"))
                .anyMatch(a -> a.endsWith("target=/cache,readonly"));
        assertThat(after(args, "--workdir")).isEqualTo("/work");
    }

    @Test
    void theNetworkIsOnlyOpenedWhenAskedFor() {
        List<String> args =
                DockerCommand.run(spec().withNetwork(NetworkMode.BRIDGE), "n", CONFIG, 1);
        assertThat(after(args, "--network")).isEqualTo("bridge");
    }

    @Test
    void missingImagesAreOnlyPulledWhenConfigured() {
        DockerConfig pulling =
                new DockerConfig("docker", "1000:1000", "256m", true, Duration.ofSeconds(5));
        assertThat(after(DockerCommand.run(spec(), "n", pulling, 1), "--pull"))
                .isEqualTo("missing");
    }

    @Test
    void hostPathsThatCouldBreakTheMountSyntaxAreRefused() {
        SandboxSpec risky =
                spec().withMounts(List.of(Mount.readWrite(Path.of("a,source=/etc"), "/work")));
        assertThatThrownBy(() -> DockerCommand.run(risky, "n", CONFIG, 1))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("reserved character");
    }

    @Test
    void configurationIsValidated() {
        assertThatThrownBy(() -> new DockerConfig(" ", "1000", "256m", false, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DockerConfig("docker", "1000", "lots", false, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CONFIG.withUser("0:0").user()).isEqualTo("0:0");
        assertThat(CONFIG.withBinary("podman").binary()).isEqualTo("podman");
    }

    @Test
    void theReaperPicksOnlyContainersOlderThanTheLimit() {
        String listing =
                "rook-old 1000\nrook-new 99990\nrook-bad notanumber\nstray\nrook-edge 99900\n";
        assertThat(DockerReaper.stale(listing, 100_000, Duration.ofSeconds(100)))
                .containsExactly("rook-old", "rook-edge");
        assertThat(DockerReaper.stale("", 100_000, Duration.ofSeconds(1))).isEmpty();
    }
}
