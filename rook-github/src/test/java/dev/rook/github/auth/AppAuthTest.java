package dev.rook.github.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.testkit.TestClock;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AppAuthTest {

    private static KeyPair pair;

    @BeforeAll
    static void generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
    }

    private static String pem(String label, byte[] der) {
        String body =
                Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    private static byte[] pkcs1(PrivateKey key) {
        byte[] pkcs8 = key.getEncoded();
        return Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
    }

    @Test
    void pkcs8AndPkcs1KeysParseToTheSameKey() {
        PrivateKey fromPkcs8 =
                PrivateKeys.parseRsa(pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        PrivateKey fromPkcs1 =
                PrivateKeys.parseRsa(pem("RSA PRIVATE KEY", pkcs1(pair.getPrivate())));
        assertThat(fromPkcs8.getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
        assertThat(fromPkcs1.getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
    }

    @Test
    void unusableKeysAreRejectedWithoutLeakingContent() {
        assertThatThrownBy(() -> PrivateKeys.parseRsa(null)).isInstanceOf(RookException.class);
        assertThatThrownBy(() -> PrivateKeys.parseRsa(" ")).isInstanceOf(RookException.class);
        assertThatThrownBy(() -> PrivateKeys.parseRsa("not a key at all"))
                .isInstanceOf(RookException.class)
                .hasMessageNotContaining("not a key");
        assertThatThrownBy(() -> PrivateKeys.parseRsa(pem("PRIVATE KEY", new byte[] {1, 2, 3})))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(
                        () ->
                                PrivateKeys.parseRsa(
                                        "-----BEGIN PRIVATE KEY-----\n%%%\n-----END PRIVATE KEY-----"))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void theJwtIsSignedWithRs256AndCarriesTheAppClaims() throws Exception {
        TestClock clock = TestClock.at("2026-10-01T10:00:00Z");
        String token = new AppJwt(424242, pair.getPrivate(), clock).create();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header =
                Json.tree(
                        new String(
                                Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        JsonNode claims =
                Json.tree(
                        new String(
                                Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertThat(header.get("alg").asString()).isEqualTo("RS256");
        assertThat(header.get("typ").asString()).isEqualTo("JWT");
        assertThat(claims.get("iss").asString()).isEqualTo("424242");
        long issued = claims.get("iat").asLong();
        long expires = claims.get("exp").asLong();
        assertThat(issued).isEqualTo(Instant.parse("2026-10-01T09:59:00Z").getEpochSecond());
        assertThat(expires - issued).isLessThanOrEqualTo(600);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        assertThat(token).doesNotContain("=");
    }

    @Test
    void aTamperedJwtDoesNotVerify() throws Exception {
        String token =
                new AppJwt(1, pair.getPrivate(), TestClock.at("2026-10-01T10:00:00Z")).create();
        String[] parts = token.split("\\.");
        String forged =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"iss\":\"2\",\"iat\":1,\"exp\":9999999999}"
                                        .getBytes(StandardCharsets.UTF_8));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + forged).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isFalse();
    }

    private static final class CountingIssuer implements InstallationTokens.Issuer {

        final AtomicInteger calls = new AtomicInteger();
        final TestClock clock;
        final Duration lifetime;

        CountingIssuer(TestClock clock, Duration lifetime) {
            this.clock = clock;
            this.lifetime = lifetime;
        }

        @Override
        public InstallationTokens.Token issue(String appJwt, long installationId) {
            assertThat(appJwt.split("\\.")).hasSize(3);
            int n = calls.incrementAndGet();
            return new InstallationTokens.Token(
                    "ghs_" + installationId + "_" + n, clock.instant().plus(lifetime));
        }
    }

    private InstallationTokens tokens(TestClock clock, CountingIssuer issuer) {
        return new InstallationTokens(issuer, new AppJwt(7, pair.getPrivate(), clock), clock);
    }

    @Test
    void aTokenIsReusedUntilItIsCloseToExpiring() {
        TestClock clock = TestClock.at("2026-10-01T10:00:00Z");
        CountingIssuer issuer = new CountingIssuer(clock, Duration.ofHours(1));
        InstallationTokens tokens = tokens(clock, issuer);
        String first = tokens.tokenFor(11);
        clock.advance(Duration.ofMinutes(50));
        assertThat(tokens.tokenFor(11)).isEqualTo(first);
        clock.advance(Duration.ofMinutes(6));
        String refreshed = tokens.tokenFor(11);
        assertThat(refreshed).isNotEqualTo(first);
        assertThat(issuer.calls).hasValue(2);
    }

    @Test
    void installationsHaveTheirOwnTokensAndCanBeForgotten() {
        TestClock clock = TestClock.at("2026-10-01T10:00:00Z");
        CountingIssuer issuer = new CountingIssuer(clock, Duration.ofHours(1));
        InstallationTokens tokens = tokens(clock, issuer);
        assertThat(tokens.tokenFor(1)).startsWith("ghs_1_");
        assertThat(tokens.tokenFor(2)).startsWith("ghs_2_");
        tokens.forget(1);
        assertThat(tokens.tokenFor(1)).isEqualTo("ghs_1_3");
    }

    @Test
    void concurrentCallersShareOneIssuedToken() throws Exception {
        TestClock clock = TestClock.at("2026-10-01T10:00:00Z");
        CountingIssuer issuer = new CountingIssuer(clock, Duration.ofHours(1));
        InstallationTokens tokens = tokens(clock, issuer);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 64; i++) {
            pool.submit(() -> tokens.tokenFor(5));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(issuer.calls).hasValue(1);
    }
}
