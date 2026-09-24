package dev.rook.github.auth;

import dev.rook.core.error.RookException;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class PrivateKeys {

    private static final byte[] RSA_ALGORITHM =
            new byte[] {
                0x30,
                0x0d,
                0x06,
                0x09,
                0x2a,
                (byte) 0x86,
                0x48,
                (byte) 0x86,
                (byte) 0xf7,
                0x0d,
                0x01,
                0x01,
                0x01,
                0x05,
                0x00
            };

    private PrivateKeys() {}

    public static PrivateKey parseRsa(String pem) {
        if (pem == null || pem.isBlank()) {
            throw RookException.invalid("the app private key is empty");
        }
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        boolean pkcs8 = pem.contains("BEGIN PRIVATE KEY");
        if (!pkcs1 && !pkcs8) {
            throw RookException.invalid("the app private key is not a PEM encoded RSA key");
        }
        String body =
                pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                        .replaceAll("-----END [A-Z ]+-----", "")
                        .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw RookException.invalid("the app private key is not valid base64", e);
        }
        try {
            byte[] pkcs8Der = pkcs1 ? wrap(der) : der;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Der));
        } catch (GeneralSecurityException e) {
            throw RookException.invalid("the app private key cannot be read", e);
        }
    }

    static byte[] wrap(byte[] pkcs1) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.writeBytes(new byte[] {0x02, 0x01, 0x00});
        content.writeBytes(RSA_ALGORITHM);
        content.write(0x04);
        content.writeBytes(length(pkcs1.length));
        content.writeBytes(pkcs1);
        byte[] inner = content.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.writeBytes(length(inner.length));
        out.writeBytes(inner);
        return out.toByteArray();
    }

    private static byte[] length(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        if (length < 0x100) {
            return new byte[] {(byte) 0x81, (byte) length};
        }
        if (length < 0x10000) {
            return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
        }
        return new byte[] {(byte) 0x83, (byte) (length >> 16), (byte) (length >> 8), (byte) length};
    }
}
