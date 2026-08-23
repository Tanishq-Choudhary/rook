package dev.rook.agent.patch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class UnifiedDiffPropertyTest {

    @Provide
    Arbitrary<String> texts() {
        Arbitrary<String> line =
                Arbitraries.of(
                        "alpha", "beta", "gamma", "", "    indented", "x = 1;", "}", "tail\r");
        return Arbitraries.integers()
                .between(0, 40)
                .flatMap(
                        n ->
                                line.list()
                                        .ofSize(n)
                                        .flatMap(
                                                lines ->
                                                        Arbitraries.of(true, false)
                                                                .map(
                                                                        newline ->
                                                                                join(
                                                                                        lines,
                                                                                        newline))));
    }

    private static String join(List<String> lines, boolean trailingNewline) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines) + (trailingNewline ? "\n" : "");
    }

    @Property(tries = 400)
    void applyingTheDiffToTheOriginalGivesTheTarget(
            @ForAll("texts") String before, @ForAll("texts") String after) {
        String diff = UnifiedDiff.generate("f.txt", before, after);
        if (before.equals(after)) {
            assertThat(diff).isEmpty();
            return;
        }
        assertThat(UnifiedDiff.apply(before, diff)).isEqualTo(after);
    }

    @Property(tries = 200)
    void aTextAlwaysRoundTripsToAndFromNothing(@ForAll("texts") String text) {
        if (text.isEmpty()) {
            return;
        }
        String created = UnifiedDiff.generate("f.txt", null, text);
        assertThat(UnifiedDiff.parse(created).get(0).applyTo(null)).isEqualTo(text);
        String deleted = UnifiedDiff.generate("f.txt", text, null);
        assertThat(UnifiedDiff.parse(deleted).get(0).applyTo(text)).isNull();
    }

    @Property(tries = 300)
    void theDiffNeverTouchesMoreLinesThanTheTwoTextsHold(
            @ForAll("texts") String before, @ForAll("texts") String after) {
        if (before.equals(after)) {
            return;
        }
        FileDiff diff = UnifiedDiff.parse(UnifiedDiff.generate("f", before, after)).get(0);
        int bound = TextLines.parse(before).lines().size() + TextLines.parse(after).lines().size();
        assertThat(diff.changedLines()).isLessThanOrEqualTo(bound);
    }

    @Property(tries = 200)
    void aDiffAgainstADifferentOriginalIsNeverSilentlyMisapplied(
            @ForAll("texts") String before,
            @ForAll("texts") String after,
            @ForAll("texts") String other) {
        if (before.equals(after) || before.equals(other)) {
            return;
        }
        String diff = UnifiedDiff.generate("f", before, after);
        try {
            String result = UnifiedDiff.apply(other, diff);
            FileDiff parsed = UnifiedDiff.parse(diff).get(0);
            assertThat(UnifiedDiff.generate("f", other, result)).isNotNull();
            assertThat(parsed.hunks()).isNotEmpty();
        } catch (PatchRejected expected) {
            assertThat(expected.reason()).isNotNull();
        }
    }
}
