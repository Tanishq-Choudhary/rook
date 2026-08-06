package dev.rook.changes.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RenameMatcherTest {

    private static Map<String, String> types(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void matchesSimilarNamesWithTheSameType() {
        List<RenameMatcher.Rename> renames =
                RenameMatcher.match(
                        List.of("customer_id"),
                        List.of("customer", "note"),
                        types("customer_id", "string"),
                        types("customer", "string", "note", "string"));
        assertThat(renames).hasSize(1);
        assertThat(renames.get(0).from()).isEqualTo("customer_id");
        assertThat(renames.get(0).to()).isEqualTo("customer");
        assertThat(renames.get(0).confidence()).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void doesNotMatchAcrossDifferentTypes() {
        List<RenameMatcher.Rename> renames =
                RenameMatcher.match(
                        List.of("customer_id"),
                        List.of("customer"),
                        types("customer_id", "string"),
                        types("customer", "integer"));
        assertThat(renames).isEmpty();
    }

    @Test
    void aLonePairOfTheSameTypeIsAcceptedWithLowConfidence() {
        List<RenameMatcher.Rename> renames =
                RenameMatcher.match(
                        List.of("starting_after"),
                        List.of("cursor"),
                        types("starting_after", "string"),
                        types("cursor", "string"));
        assertThat(renames).hasSize(1);
        assertThat(renames.get(0).confidence()).isEqualTo(RenameMatcher.LONE_PAIR_CONFIDENCE);
    }

    @Test
    void ambiguousLeftoversAreNotGuessed() {
        List<RenameMatcher.Rename> renames =
                RenameMatcher.match(
                        List.of("starting_after"),
                        List.of("cursor", "status"),
                        types("starting_after", "string"),
                        types("cursor", "string", "status", "string"));
        assertThat(renames).isEmpty();
    }

    @Test
    void eachNameIsUsedAtMostOnce() {
        List<RenameMatcher.Rename> renames =
                RenameMatcher.match(
                        List.of("user_name", "user_names"),
                        List.of("username"),
                        types("user_name", "string", "user_names", "string"),
                        types("username", "string"));
        assertThat(renames).hasSize(1);
        assertThat(renames.get(0).from()).isEqualTo("user_name");
    }

    @Test
    void bestScoreWinsBetweenCompetingCandidates() {
        List<RenameMatcher.Rename> renames =
                RenameMatcher.match(
                        List.of("amount_cents"),
                        List.of("amount", "amount_minor"),
                        types("amount_cents", "integer"),
                        types("amount", "integer", "amount_minor", "integer"));
        assertThat(renames).hasSize(1);
        assertThat(renames.get(0).to()).isIn("amount", "amount_minor");
    }

    @Test
    void lonePairsAreFoundPerTypeGroup() {
        List<RenameMatcher.Rename> renames =
                RenameMatcher.match(
                        List.of("a", "b"),
                        List.of("x"),
                        types("a", "int", "b", "string"),
                        types("x", "string"));
        assertThat(renames).hasSize(1);
        assertThat(renames.get(0).from()).isEqualTo("b");
        assertThat(renames.get(0).to()).isEqualTo("x");
    }

    @Test
    void anAddedPrefixCountsAsASimilarName() {
        List<RenameMatcher.Rename> renames =
                RenameMatcher.match(
                        List.of("charge"),
                        List.of("createCharge"),
                        types("charge", "sig"),
                        types("createCharge", "sig"));
        assertThat(renames).hasSize(1);
        assertThat(renames.get(0).confidence()).isGreaterThanOrEqualTo(0.7);
    }
}
