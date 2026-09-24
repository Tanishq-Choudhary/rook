package dev.rook.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final String SECRET = "a-long-enough-webhook-secret";
    private final SignatureVerifier verifier = new SignatureVerifier(SECRET);

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void matchesTheDocumentedGithubExample() {
        SignatureVerifier example = new SignatureVerifier("It's a Secret to Everybody");
        assertThat(example.sign(bytes("Hello, World!")))
                .isEqualTo(
                        "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
        assertThat(
                        example.verify(
                                bytes("Hello, World!"),
                                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"))
                .isTrue();
    }

    @Test
    void aSignatureOverTheSameBodyVerifies() {
        byte[] body = bytes("{\"action\":\"created\"}");
        assertThat(verifier.verify(body, verifier.sign(body))).isTrue();
    }

    @Test
    void aChangedBodyOrASecretFromElsewhereFails() {
        byte[] body = bytes("{\"action\":\"created\"}");
        String signature = verifier.sign(body);
        assertThat(verifier.verify(bytes("{\"action\":\"deleted\"}"), signature)).isFalse();
        assertThat(new SignatureVerifier("another-long-enough-secret").verify(body, signature))
                .isFalse();
    }

    @Test
    void malformedHeadersAreRefused() {
        byte[] body = bytes("x");
        String valid = verifier.sign(body);
        assertThat(verifier.verify(body, null)).isFalse();
        assertThat(verifier.verify(null, valid)).isFalse();
        assertThat(verifier.verify(body, "")).isFalse();
        assertThat(verifier.verify(body, valid.substring("sha256=".length()))).isFalse();
        assertThat(verifier.verify(body, "sha1=" + valid.substring(7))).isFalse();
        assertThat(verifier.verify(body, "sha256=zz")).isFalse();
        assertThat(verifier.verify(body, "sha256=")).isFalse();
        assertThat(verifier.verify(body, valid.substring(0, valid.length() - 2))).isFalse();
        assertThat(verifier.verify(body, valid.toUpperCase())).isFalse();
    }

    @Test
    void weakSecretsAreRefusedUpFront() {
        assertThatThrownBy(() -> new SignatureVerifier("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignatureVerifier(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void everySignedBodyVerifiesAndNothingElseDoes(@ForAll String body, @ForAll String other) {
        String signature = verifier.sign(bytes(body));
        assertThat(verifier.verify(bytes(body), signature)).isTrue();
        if (!body.equals(other)) {
            assertThat(verifier.verify(bytes(other), signature)).isFalse();
        }
    }
}
