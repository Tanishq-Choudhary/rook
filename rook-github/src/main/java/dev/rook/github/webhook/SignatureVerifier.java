package dev.rook.github.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public SignatureVerifier(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("the webhook secret needs at least 16 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(byte[] body, String header) {
        if (body == null || header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(header.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(body), presented);
    }

    public String sign(byte[] body) {
        return PREFIX + HexFormat.of().formatHex(hmac(body));
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
