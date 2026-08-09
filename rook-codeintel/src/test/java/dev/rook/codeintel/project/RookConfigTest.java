package dev.rook.codeintel.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RookConfigTest {

    private static final String FULL =
            """
            version: 1
            watch:
              - source: acme-sdk
                coordinates: com.acme:acme-sdk
              - source: acme-api
            paths:
              include: [src/main/java, src/test/java]
              exclude: [src/generated]
            verify:
              command: mvn -B -ntp test
              timeoutSeconds: 900
            limits:
              maxFilesChanged: 10
              maxLinesChanged: 200
            """;

    @Test
    void parsesAFullConfiguration() {
        RookConfig config = RookConfig.parse(FULL);
        assertThat(config.watch()).hasSize(2);
        assertThat(config.watch().get(0).coordinates()).isEqualTo("com.acme:acme-sdk");
        assertThat(config.paths().include()).containsExactly("src/main/java", "src/test/java");
        assertThat(config.paths().exclude()).containsExactly("src/generated");
        assertThat(config.verify().command()).isEqualTo("mvn -B -ntp test");
        assertThat(config.verify().timeoutSeconds()).isEqualTo(900);
        assertThat(config.limits().maxFilesChanged()).isEqualTo(10);
        assertThat(config.limits().maxLinesChanged()).isEqualTo(200);
    }

    @Test
    void missingSectionsFallBackToDefaults() {
        RookConfig config = RookConfig.parse("version: 1\n");
        assertThat(config.watch()).isEmpty();
        assertThat(config.verify().command()).isNull();
        assertThat(config.verify().timeoutSeconds()).isEqualTo(600);
        assertThat(config.limits().maxFilesChanged()).isEqualTo(25);
        assertThat(config.limits().maxLinesChanged()).isEqualTo(500);
    }

    @Test
    void emptyDocumentsAreTheDefaultConfiguration() {
        assertThat(RookConfig.parse("")).isEqualTo(RookConfig.defaults());
        assertThat(RookConfig.parse(null)).isEqualTo(RookConfig.defaults());
    }

    @Test
    void unknownFieldsAreRejectedSoTyposDoNotPassSilently() {
        assertThatThrownBy(() -> RookConfig.parse("version: 1\nwatches: []\n"))
                .isInstanceOf(RookException.class)
                .hasMessageContaining(".rook.yml is invalid");
    }

    @Test
    void onlyVersionOneIsSupported() {
        assertThatThrownBy(() -> RookConfig.parse("version: 2\n"))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("version 2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "/abs/path", "C:/win", "a/../../b", ".."})
    void unsafePathsAreRejected(String path) {
        String yaml = "version: 1\npaths:\n  exclude: [\"" + path + "\"]\n";
        assertThatThrownBy(() -> RookConfig.parse(yaml)).isInstanceOf(RookException.class);
    }

    @Test
    void sourceIdsMustBeSlugs() {
        assertThatThrownBy(() -> RookConfig.parse("watch:\n  - source: Bad Name\n"))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(
                        () ->
                                RookConfig.parse(
                                        "watch:\n  - source: ok\n    coordinates: nocolon\n"))
                .isInstanceOf(RookException.class);
    }

    @Test
    void verifyCommandAndTimeoutAreBounded() {
        assertThatThrownBy(() -> RookConfig.parse("verify:\n  command: \"a\\nb\"\n"))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> RookConfig.parse("verify:\n  timeoutSeconds: 5\n"))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> RookConfig.parse("verify:\n  timeoutSeconds: 99999\n"))
                .isInstanceOf(RookException.class);
    }

    @Test
    void limitsMustBePositiveAndReasonable() {
        assertThatThrownBy(() -> RookConfig.parse("limits:\n  maxFilesChanged: 0\n"))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> RookConfig.parse("limits:\n  maxLinesChanged: 1000000\n"))
                .isInstanceOf(RookException.class);
    }

    @Test
    void malformedYamlIsAnInvalidRequest() {
        assertThatThrownBy(() -> RookConfig.parse("watch: [unclosed"))
                .isInstanceOf(RookException.class);
    }
}
