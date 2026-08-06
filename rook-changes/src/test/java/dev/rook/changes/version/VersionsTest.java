package dev.rook.changes.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersionsTest {

    @ParameterizedTest
    @CsvSource({
        "1.0.0, 1.0.1, -1",
        "1.0.1, 1.0.0, 1",
        "1.0, 1.0.0, 0",
        "1.9.0, 1.10.0, -1",
        "2.0.0, 10.0.0, -1",
        "1.0.0-rc1, 1.0.0, -1",
        "1.0.0-alpha, 1.0.0-beta, -1",
        "1.0.0-beta.2, 1.0.0-beta.10, -1",
        "1.0.0-beta, 1.0.0-rc1, -1",
        "1.0.0-SNAPSHOT, 1.0.0, -1",
        "1.0.0-rc1, 1.0.0-rc2, -1",
        "1.0.0.RELEASE, 1.0.0, 0",
        "1.0.0-sp1, 1.0.0, 1",
        "1.0.0, 1.0.0-1, -1",
        "2024.01.5, 2024.1.5, 0"
    })
    void orderingMatchesMavenIntuition(String left, String right, int expected) {
        assertThat(Integer.signum(Versions.compare(left, right))).isEqualTo(expected);
    }

    @Test
    void detectsPrereleases() {
        assertThat(Versions.isPrerelease("1.0.0-rc1")).isTrue();
        assertThat(Versions.isPrerelease("2.0.0-beta.3")).isTrue();
        assertThat(Versions.isPrerelease("1.0.0-SNAPSHOT")).isTrue();
        assertThat(Versions.isPrerelease("1.0.0")).isFalse();
        assertThat(Versions.isPrerelease("1.0.0.RELEASE")).isFalse();
        assertThat(Versions.isPrerelease("1.0.0-sp1")).isFalse();
    }

    @Test
    void latestSkipsPrereleasesUnlessAsked() {
        List<String> versions = List.of("1.0.0", "1.1.0", "2.0.0-rc1");
        assertThat(Versions.latest(versions, false)).contains("1.1.0");
        assertThat(Versions.latest(versions, true)).contains("2.0.0-rc1");
        assertThat(Versions.latest(List.of(), false)).isEmpty();
    }

    @Test
    void newerThanReturnsAscendingVersionsAfterTheKnownOne() {
        List<String> versions = List.of("1.2.0", "1.0.0", "1.1.0", "1.3.0-beta", "1.3.0");
        assertThat(Versions.newerThan(versions, "1.1.0", false)).containsExactly("1.2.0", "1.3.0");
        assertThat(Versions.newerThan(versions, "1.1.0", true))
                .containsExactly("1.2.0", "1.3.0-beta", "1.3.0");
        assertThat(Versions.newerThan(versions, "9.9.9", false)).isEmpty();
    }

    @Test
    void sortedIsAscending() {
        assertThat(Versions.sorted(List.of("1.10.0", "1.2.0", "1.2.0-rc1", "0.9")))
                .containsExactly("0.9", "1.2.0-rc1", "1.2.0", "1.10.0");
    }

    @Property
    void compareIsReflexive(@ForAll("versions") String version) {
        assertThat(Versions.compare(version, version)).isZero();
    }

    @Property
    void compareIsAntisymmetric(@ForAll("versions") String a, @ForAll("versions") String b) {
        assertThat(Integer.signum(Versions.compare(a, b)))
                .isEqualTo(-Integer.signum(Versions.compare(b, a)));
    }

    @Property
    void compareIsTransitive(
            @ForAll("versions") String a,
            @ForAll("versions") String b,
            @ForAll("versions") String c) {
        List<String> sorted = Versions.sorted(List.of(a, b, c));
        assertThat(Versions.compare(sorted.get(0), sorted.get(1))).isLessThanOrEqualTo(0);
        assertThat(Versions.compare(sorted.get(1), sorted.get(2))).isLessThanOrEqualTo(0);
        assertThat(Versions.compare(sorted.get(0), sorted.get(2))).isLessThanOrEqualTo(0);
    }

    @Provide
    Arbitrary<String> versions() {
        Arbitrary<Integer> number = Arbitraries.integers().between(0, 30);
        Arbitrary<String> qualifier =
                Arbitraries.of(
                        "", "-alpha", "-beta", "-rc1", "-rc2", "-SNAPSHOT", "-sp1", ".RELEASE");
        return Combinators.combine(number, number, number, qualifier)
                .as((major, minor, patch, q) -> major + "." + minor + "." + patch + q);
    }
}
