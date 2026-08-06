package dev.rook.changes.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

public final class AddressGuard {

    private AddressGuard() {}

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(bytes);
        }
        if (address instanceof Inet6Address) {
            if (isV4Mapped(bytes)) {
                return isPublicV4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
            }
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false;
            }
            return !(first == 0x20
                    && bytes[1] == 0x01
                    && bytes[2] == 0x0d
                    && bytes[3] == (byte) 0xb8);
        }
        return false;
    }

    private static boolean isV4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static boolean isPublicV4(byte[] bytes) {
        int a = bytes[0] & 0xff;
        int b = bytes[1] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224) {
            return false;
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return false;
        }
        if (a == 169 && b == 254) {
            return false;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return false;
        }
        if (a == 192 && b == 168) {
            return false;
        }
        if (a == 192 && b == 0 && (bytes[2] & 0xff) == 0) {
            return false;
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return false;
        }
        return true;
    }
}
