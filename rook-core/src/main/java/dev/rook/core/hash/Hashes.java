package dev.rook.core.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {

    private static final HexFormat HEX = HexFormat.of();

    private Hashes() {}

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        return HEX.formatHex(digest().digest(value));
    }

    public static String sha256Hex(String... parts) {
        MessageDigest md = digest();
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            md.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            md.update((byte) ':');
            md.update(bytes);
        }
        return HEX.formatHex(md.digest());
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
