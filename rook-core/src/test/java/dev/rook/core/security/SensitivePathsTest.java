package dev.rook.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SensitivePathsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".env",
                ".env.local",
                "config/.env.production",
                ".npmrc",
                "home/.aws/credentials",
                "deploy/id_rsa",
                "keys/server.pem",
                "keys/private.key",
                "store/cert.p12",
                "app/keystore.jks",
                "credentials",
                "config/credentials.json",
                "secrets.yml",
                "config/secret.properties",
                "infra/service-account-prod.json",
                "src/main/resources/application-prod.yml",
                "infra/terraform.tfstate",
                "infra/prod.tfvars",
                "CONFIG\\.ENV"
            })
    void flagsSecretBearingFiles(String path) {
        assertThat(SensitivePaths.isSensitive(path)).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "src/main/java/App.java",
                "README.md",
                "pom.xml",
                "src/main/resources/application.yml",
                "src/main/resources/application-dev.yml",
                "docs/environment.md",
                "src/test/resources/fixtures/charge.json",
                "keystore-guide.txt",
                "src/main/java/SecretsLoader.java"
            })
    void leavesOrdinaryFilesAlone(String path) {
        assertThat(SensitivePaths.isSensitive(path)).as(path).isFalse();
    }
}
