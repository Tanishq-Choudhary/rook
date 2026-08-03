package dev.rook.changes.feed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.changes.feed.ChangelogClassifier.Category;
import dev.rook.changes.feed.ChangelogClassifier.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangelogClassifierTest {

    private static final String KEEP_A_CHANGELOG =
            """
            ## [2.0.0] - 2026-07-01

            ### Added
            - Support for payouts
            - Faster retries

            ### Changed
            - `PaymentClient.charge` has been renamed to `PaymentClient.createCharge`
            - The default timeout is now 30 seconds

            ### Deprecated
            - `RefundRequest.reason` is deprecated, use `RefundRequest.note` instead

            ### Removed
            - Removed the `LegacyAuth` class

            ### Fixed
            - Fix retry loop on 429
            """;

    @Test
    void ignoresAddedAndFixedEntries() {
        List<Finding> findings = ChangelogClassifier.classify(KEEP_A_CHANGELOG);
        assertThat(findings)
                .noneMatch(f -> f.text().contains("payouts"))
                .noneMatch(f -> f.text().contains("retry loop"))
                .noneMatch(f -> f.text().contains("timeout"));
    }

    @Test
    void readsRenamesWithBothSides() {
        List<Finding> findings = ChangelogClassifier.classify(KEEP_A_CHANGELOG);
        Finding rename =
                findings.stream()
                        .filter(f -> f.category() == Category.RENAME)
                        .findFirst()
                        .orElseThrow();
        assertThat(rename.from()).isEqualTo("PaymentClient.charge");
        assertThat(rename.replacement()).isEqualTo("PaymentClient.createCharge");
    }

    @Test
    void readsDeprecationsWithReplacement() {
        List<Finding> findings = ChangelogClassifier.classify(KEEP_A_CHANGELOG);
        Finding deprecation =
                findings.stream()
                        .filter(f -> f.category() == Category.DEPRECATION)
                        .findFirst()
                        .orElseThrow();
        assertThat(deprecation.from()).isEqualTo("RefundRequest.reason");
        assertThat(deprecation.replacement()).isEqualTo("RefundRequest.note");
    }

    @Test
    void sectionHeadingDecidesTheCategoryWhenTheLineIsPlain() {
        List<Finding> findings = ChangelogClassifier.classify(KEEP_A_CHANGELOG);
        Finding removal =
                findings.stream()
                        .filter(f -> f.text().contains("LegacyAuth"))
                        .findFirst()
                        .orElseThrow();
        assertThat(removal.category()).isEqualTo(Category.REMOVAL);
        assertThat(removal.from()).isEqualTo("LegacyAuth");
        assertThat(removal.replacement()).isNull();
    }

    @Test
    void breakingMarkersInlineAreRecognised() {
        List<Finding> findings =
                ChangelogClassifier.classify(
                        "- BREAKING: `Client.send` now requires a `Region` argument\n- Minor wording fix\n");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).category()).isEqualTo(Category.BREAKING);
        assertThat(findings.get(0).symbols()).containsExactly("Client.send", "Region");
    }

    @Test
    void useInsteadPatternGivesTheReplacement() {
        List<Finding> findings =
                ChangelogClassifier.classify(
                        "### Deprecations\n- Use `createCharge` instead of `charge`\n");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).from()).isEqualTo("charge");
        assertThat(findings.get(0).replacement()).isEqualTo("createCharge");
    }

    @Test
    void arrowPatternGivesBothSides() {
        List<Finding> findings =
                ChangelogClassifier.classify(
                        "### Breaking changes\n- `amount_cents` -> `amount`\n");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).from()).isEqualTo("amount_cents");
        assertThat(findings.get(0).replacement()).isEqualTo("amount");
    }

    @Test
    void wrappedBulletLinesAreJoined() {
        List<Finding> findings =
                ChangelogClassifier.classify(
                        "### Removed\n- Removed `Client.close`\n  because it never did anything useful\n");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).text()).contains("never did anything");
    }

    @Test
    void boldHeadingsActAsSections() {
        List<Finding> findings =
                ChangelogClassifier.classify(
                        "**Breaking changes**\n\n- `A.b` now returns `long`\n");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).category()).isEqualTo(Category.BREAKING);
    }

    @Test
    void plainReleaseNotesWithNothingRelevantProduceNothing() {
        assertThat(ChangelogClassifier.classify("### Added\n- New docs\n### Fixed\n- Typos\n"))
                .isEmpty();
        assertThat(ChangelogClassifier.classify("")).isEmpty();
    }
}
