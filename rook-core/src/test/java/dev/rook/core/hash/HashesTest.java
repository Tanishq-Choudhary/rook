package dev.rook.core.hash;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class HashesTest {

    @Test
    void matchesKnownVector() {
        assertThat(Hashes.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void emptyInputHasKnownDigest() {
        assertThat(Hashes.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void multiPartHashIsNotAmbiguousAcrossBoundaries() {
        assertThat(Hashes.sha256Hex("ab", "c")).isNotEqualTo(Hashes.sha256Hex("a", "bc"));
        assertThat(Hashes.sha256Hex("", "abc")).isNotEqualTo(Hashes.sha256Hex("abc", ""));
    }

    @Test
    void constantTimeEqualsComparesContent() {
        assertThat(Hashes.constantTimeEquals("secret", "secret")).isTrue();
        assertThat(Hashes.constantTimeEquals("secret", "Secret")).isFalse();
        assertThat(Hashes.constantTimeEquals("secret", "secrets")).isFalse();
    }

    @Property
    void digestIsAlwaysSixtyFourLowerHexChars(@ForAll String input) {
        assertThat(Hashes.sha256Hex(input)).matches("[0-9a-f]{64}");
    }

    @Property
    void digestIsDeterministic(@ForAll String input) {
        assertThat(Hashes.sha256Hex(input)).isEqualTo(Hashes.sha256Hex(input));
    }

    @Property
    void partsHashDiffersWhenAPartChanges(@ForAll String a, @ForAll String b, @ForAll String c) {
        net.jqwik.api.Assume.that(!b.equals(c));
        assertThat(Hashes.sha256Hex(a, b)).isNotEqualTo(Hashes.sha256Hex(a, c));
    }
}
