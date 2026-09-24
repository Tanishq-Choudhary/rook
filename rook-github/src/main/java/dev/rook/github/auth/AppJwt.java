package dev.rook.github.auth;

import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AppJwt {

    static final Duration SKEW_ALLOWANCE = Duration.ofSeconds(60);
    static final Duration LIFETIME = Duration.ofMinutes(9);

    private final long appId;
    private final PrivateKey key;
    private final Clock clock;

    public AppJwt(long appId, PrivateKey key, Clock clock) {
        this.appId = appId;
        this.key = key;
        this.clock = clock;
    }

    public String create() {
        Instant now = clock.instant();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iat", now.minus(SKEW_ALLOWANCE).getEpochSecond());
        claims.put("exp", now.plus(LIFETIME).getEpochSecond());
        claims.put("iss", Long.toString(appId));
        String signingInput = encode(Json.write(header)) + "." + encode(Json.write(claims));
        return signingInput + "." + sign(signingInput);
    }

    private String sign(String input) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw RookException.invalid("the app private key cannot sign", e);
        }
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
