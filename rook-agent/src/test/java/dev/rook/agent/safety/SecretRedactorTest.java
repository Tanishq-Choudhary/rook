package dev.rook.agent.safety;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor();

    private static String fake(String prefix, int length) {
        return prefix + "Ab3dE9gH1jK5mN7pQ2sT4vX6zC8fL0wY".repeat(4).substring(0, length);
    }

    @Test
    void awsAccessKeysAreRedacted() {
        String key = "AKIA" + "ABCDEFGHIJKLMNOP";
        SecretRedactor.Result result = redactor.redactWithReport("id = " + key + " end");
        assertThat(result.text()).doesNotContain(key).contains("[REDACTED:aws-access-key]");
        assertThat(result.kinds()).contains("aws-access-key");
        assertThat(result.changed()).isTrue();
    }

    @Test
    void githubTokensInBothFormatsAreRedacted() {
        String classic = fake("ghp_", 36);
        String fine = fake("github_pat_", 40);
        String text = redactor.redact("a " + classic + " b " + fine);
        assertThat(text).doesNotContain(classic).doesNotContain(fine);
        assertThat(text).contains("[REDACTED:github-token]");
    }

    @Test
    void privateKeyBlocksAreRemovedEntirely() {
        String block =
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\nabcdef123456\n-----END RSA PRIVATE KEY-----";
        String text = redactor.redact("before\n" + block + "\nafter");
        assertThat(text).isEqualTo("before\n[REDACTED:private-key]\nafter");
    }

    @Test
    void anUnterminatedPrivateKeyBlockIsStillRedactedToTheEnd() {
        String text =
                redactor.redact("x\n-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC");
        assertThat(text).doesNotContain("MIIEvQ").contains("[REDACTED:private-key]");
    }

    @Test
    void jwtsAreRedacted() {
        String jwt =
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r";
        assertThat(redactor.redact("Authorization: " + jwt)).doesNotContain(jwt);
    }

    @Test
    void slackStripeAndModelProviderKeysAreRedacted() {
        String slack = "xox" + "b-123456789012-abcdefghij";
        String stripe = "sk_" + "live_" + "abcdefghijklmnop1234";
        String provider = "sk-" + "proj-abcdefghijklmnopqrstuvwxyz";
        String google = "AIza" + "SyA1234567890abcdefghijklmnopqrstuv";
        String text = redactor.redact(String.join(" ", slack, stripe, provider, google));
        assertThat(text)
                .doesNotContain(slack)
                .doesNotContain(stripe)
                .doesNotContain(provider)
                .doesNotContain(google);
    }

    @Test
    void credentialsInUrlsAreRedactedButTheHostIsKept() {
        String text = redactor.redact("jdbc:postgresql://admin:hunter22pw@db.internal:5432/app");
        assertThat(text).doesNotContain("hunter22pw").contains("db.internal:5432/app");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password = s3cr3tValue99",
                "db.password=s3cr3tValue99",
                "API_KEY=s3cr3tValue99",
                "\"client_secret\": \"s3cr3tValue99\"",
                "auth-token: s3cr3tValue99",
                "AWS_SECRET_ACCESS_KEY=s3cr3tValue99xx",
                "private_key: s3cr3tValue99"
            })
    void sensitiveAssignmentsLoseTheirValue(String line) {
        String text = redactor.redact(line);
        assertThat(text).doesNotContain("s3cr3tValue99");
        assertThat(text).contains("[REDACTED:assignment]");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password = getPassword()",
                "String token = request.header(\"x\");",
                "private static final String TOKEN_URL = \"https://example.com/oauth/token\";",
                "password: ${DB_PASSWORD}",
                "secret = <your secret here>",
                "token=short",
                "passwordEncoder.encode(raw)",
                "int authorityCount = 3;"
            })
    void ordinaryCodeIsLeftAlone(String line) {
        assertThat(redactor.redact(line)).isEqualTo(line);
    }

    @Test
    void textWithoutSecretsIsReturnedUnchanged() {
        String code = "public class A { int x = 1; String name = \"orders\"; }";
        SecretRedactor.Result result = redactor.redactWithReport(code);
        assertThat(result.text()).isEqualTo(code);
        assertThat(result.changed()).isFalse();
        assertThat(result.kinds()).isEmpty();
    }

    @Test
    void emptyAndNullInputsAreHandled() {
        assertThat(redactor.redact(null)).isEmpty();
        assertThat(redactor.redact("")).isEmpty();
    }

    @Test
    void severalSecretsInOneTextAreAllCounted() {
        String text =
                "AKIA"
                        + "ABCDEFGHIJKLMNOP"
                        + " and "
                        + fake("ghp_", 36)
                        + " and password=s3cr3tValue99";
        SecretRedactor.Result result = redactor.redactWithReport(text);
        assertThat(result.count()).isEqualTo(3);
        assertThat(result.kinds())
                .containsExactlyInAnyOrder("aws-access-key", "github-token", "assignment");
    }

    @Provide
    Arbitrary<String> tokens() {
        return Arbitraries.strings().withCharRange('a', 'z').withCharRange('0', '9').ofLength(40);
    }

    @Provide
    Arbitrary<String> plainWords() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
    }

    @Property
    void embeddedGithubTokensNeverSurvive(
            @ForAll("tokens") String body, @ForAll("plainWords") String around) {
        String secret = "ghp_" + body;
        String text = around + " " + secret + " " + around;
        assertThat(redactor.redact(text)).doesNotContain(secret);
    }

    @Property
    void redactionIsIdempotent(@ForAll("tokens") String body, @ForAll("plainWords") String word) {
        String text = word + " password=" + body + " ghp_" + body;
        String once = redactor.redact(text);
        assertThat(redactor.redact(once)).isEqualTo(once);
    }

    @Property
    void plainWordsAreNeverTouched(@ForAll("plainWords") String a, @ForAll("plainWords") String b) {
        String text = a + " " + b;
        assertThat(redactor.redact(text)).isEqualTo(text);
    }
}
