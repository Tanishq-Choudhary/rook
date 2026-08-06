package dev.rook.changes.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressGuardTest {

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "127.8.8.8",
                "10.0.0.5",
                "172.16.0.1",
                "172.31.255.255",
                "192.168.1.1",
                "169.254.169.254",
                "0.0.0.0",
                "100.64.0.1",
                "100.127.255.255",
                "224.0.0.1",
                "239.255.255.255",
                "255.255.255.255",
                "198.18.0.1",
                "192.0.0.8",
                "::1",
                "::",
                "fe80::1",
                "fc00::1",
                "fd12:3456::1",
                "ff02::1",
                "::ffff:127.0.0.1",
                "::ffff:10.0.0.1",
                "::ffff:169.254.169.254",
                "2001:db8::1"
            })
    void blocksInternalAndReservedAddresses(String literal) throws Exception {
        assertThat(AddressGuard.isPublic(ip(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "8.8.8.8",
                "1.1.1.1",
                "93.184.216.34",
                "172.32.0.1",
                "172.15.255.255",
                "100.63.255.255",
                "100.128.0.1",
                "2606:4700:4700::1111",
                "::ffff:8.8.8.8"
            })
    void allowsPublicAddresses(String literal) throws Exception {
        assertThat(AddressGuard.isPublic(ip(literal))).as(literal).isTrue();
    }
}
