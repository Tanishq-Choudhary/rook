package dev.rook.changes.diff;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class SimilarityTest {

    @Test
    void splitsSnakeCamelAndKebabCase() {
        assertThat(Similarity.tokens("customer_id")).containsExactly("customer", "id");
        assertThat(Similarity.tokens("customerId")).containsExactly("customer", "id");
        assertThat(Similarity.tokens("customer-id")).containsExactly("customer", "id");
        assertThat(Similarity.tokens("HTTPServerError")).containsExactly("http", "server", "error");
    }

    @Test
    void sameWordsInDifferentStylesAreIdentical() {
        assertThat(Similarity.names("customer_id", "customerId")).isEqualTo(1.0);
    }

    @Test
    void closeNamesScoreAboveTheRenameThreshold() {
        assertThat(Similarity.names("customer_id", "customer")).isGreaterThanOrEqualTo(0.6);
        assertThat(Similarity.names("createCharge", "createPayment")).isLessThan(0.8);
    }

    @Test
    void unrelatedNamesScoreLow() {
        assertThat(Similarity.names("starting_after", "cursor")).isLessThan(0.4);
    }

    @Test
    void emptyNamesNeverMatch() {
        assertThat(Similarity.names("", "x")).isZero();
        assertThat(Similarity.names("___", "x")).isZero();
    }

    @Test
    void editDistanceMatchesKnownCases() {
        assertThat(Similarity.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(Similarity.distance("", "abc")).isEqualTo(3);
        assertThat(Similarity.distance("same", "same")).isZero();
    }

    @Property
    void scoreIsSymmetricAndBounded(
            @ForAll @AlphaChars @StringLength(min = 1, max = 12) String left,
            @ForAll @AlphaChars @StringLength(min = 1, max = 12) String right) {
        double forward = Similarity.names(left, right);
        assertThat(forward).isBetween(0.0, 1.0);
        assertThat(forward).isEqualTo(Similarity.names(right, left));
    }

    @Property
    void distanceIsSymmetric(
            @ForAll @AlphaChars @StringLength(max = 10) String left,
            @ForAll @AlphaChars @StringLength(max = 10) String right) {
        assertThat(Similarity.distance(left, right)).isEqualTo(Similarity.distance(right, left));
    }
}
