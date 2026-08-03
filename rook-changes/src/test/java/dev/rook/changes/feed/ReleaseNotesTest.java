package dev.rook.changes.feed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.changes.diff.DiffContext;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.Severity;
import dev.rook.core.change.SymbolRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseNotesTest {

    private static final DiffContext CTX = new DiffContext("acme-sdk", "1.0.0", "2.0.0");

    private static ReleaseEntry entry(String body) {
        return new ReleaseEntry(
                "2.0.0",
                "Acme 2.0",
                Instant.parse("2026-07-01T00:00:00Z"),
                body,
                "https://example.test/2",
                false);
    }

    @Test
    void advisoryEventsAreBuiltFromFindings() {
        ReleaseNotes.Result result =
                ReleaseNotes.fromEntry(
                        "acme-sdk",
                        "1.0.0",
                        entry(
                                "### Changed\n- `Client.charge` renamed to `Client.createCharge`\n### Deprecated\n- `Refund.reason` is deprecated, use `Refund.note`\n"));
        assertThat(result.events()).hasSize(2);
        assertThat(result.events()).allMatch(e -> e.kind().isAdvisory());
        assertThat(result.events())
                .anyMatch(
                        e ->
                                e.kind() == ChangeKind.ADVISORY_BREAKING
                                        && e.severity() == Severity.BREAKING);
        assertThat(result.events())
                .anyMatch(
                        e ->
                                e.kind() == ChangeKind.ADVISORY_DEPRECATION
                                        && e.severity() == Severity.DEPRECATION);
        assertThat(result.notes()).hasSize(2);
        assertThat(result.notes().get(0).url()).isEqualTo("https://example.test/2");
    }

    @Test
    void releasesWithNothingRelevantYieldNoEvents() {
        ReleaseNotes.Result result =
                ReleaseNotes.fromEntry("acme-sdk", "1.0.0", entry("### Added\n- A thing\n"));
        assertThat(result.events()).isEmpty();
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void enrichmentFillsMissingReplacementsFromNotes() {
        ChangeEvent removed =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_REMOVED,
                        SymbolRef.javaMethod("com.acme.PaymentClient", "charge", List.of("long")),
                        Map.of(Attr.OWNER, "com.acme.PaymentClient", Attr.NAME, "charge"),
                        "removed");
        ReleaseNotes.Result notes =
                ReleaseNotes.fromEntry(
                        "acme-sdk",
                        "1.0.0",
                        entry(
                                "### Changed\n- `PaymentClient.charge` renamed to `PaymentClient.createCharge`\n"));
        List<ChangeEvent> enriched = ReleaseNotes.enrich(CTX, List.of(removed), notes.findings());
        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0).attribute(Attr.REPLACEMENT))
                .isEqualTo("PaymentClient.createCharge");
        assertThat(enriched.get(0).fingerprint()).isNotEqualTo(removed.fingerprint());
    }

    @Test
    void enrichmentLeavesExistingReplacementsAlone() {
        ChangeEvent renamed =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod("com.acme.PaymentClient", "charge", List.of("long")),
                        Map.of(Attr.REPLACEMENT, "createCharge"),
                        "renamed");
        ReleaseNotes.Result notes =
                ReleaseNotes.fromEntry(
                        "acme-sdk",
                        "1.0.0",
                        entry("### Changed\n- `charge` renamed to `somethingElse`\n"));
        assertThat(ReleaseNotes.enrich(CTX, List.of(renamed), notes.findings()))
                .containsExactly(renamed);
    }

    @Test
    void enrichmentRespectsTheOwnerWhenTheNoteNamesOne() {
        ChangeEvent removed =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_REMOVED,
                        SymbolRef.javaMethod("com.acme.RefundClient", "charge", List.of()),
                        Map.of(),
                        "removed");
        ReleaseNotes.Result notes =
                ReleaseNotes.fromEntry(
                        "acme-sdk",
                        "1.0.0",
                        entry(
                                "### Changed\n- `PaymentClient.charge` renamed to `PaymentClient.createCharge`\n"));
        assertThat(
                        ReleaseNotes.enrich(CTX, List.of(removed), notes.findings())
                                .get(0)
                                .attribute(Attr.REPLACEMENT))
                .isNull();
    }

    @Test
    void enrichmentMatchesOpenApiFieldsByName() {
        ChangeEvent removed =
                ChangeEvent.of(
                        "acme-api",
                        "1",
                        "2",
                        ChangeKind.RESPONSE_FIELD_REMOVED,
                        SymbolRef.schemaField("Charge", "amount_cents"),
                        Map.of(),
                        "removed");
        ReleaseNotes.Result notes =
                ReleaseNotes.fromEntry(
                        "acme-api",
                        "1",
                        entry(
                                "### Breaking changes\n- `Charge.amount_cents` renamed to `Charge.amount`\n"));
        List<ChangeEvent> enriched =
                ReleaseNotes.enrich(
                        new DiffContext("acme-api", "1", "2"), List.of(removed), notes.findings());
        assertThat(enriched.get(0).attribute(Attr.REPLACEMENT)).isEqualTo("Charge.amount");
    }
}
