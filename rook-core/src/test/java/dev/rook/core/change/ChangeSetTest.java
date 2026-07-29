package dev.rook.core.change;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.id.Ids;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeSetTest {

    private static ChangeEvent event(ChangeKind kind, String path) {
        return ChangeEvent.of(
                "acme", "1", "2", kind, SymbolRef.operation("GET", path), Map.of(), kind.name());
    }

    private static ChangeSet set(ChangeEvent... events) {
        return new ChangeSet(
                Ids.newId(),
                "acme",
                SourceKind.OPENAPI,
                "1",
                "2",
                List.of(events),
                List.of(),
                Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void duplicateEventsAreCollapsed() {
        ChangeEvent e = event(ChangeKind.OPERATION_REMOVED, "/a");
        assertThat(set(e, e, e).events()).hasSize(1);
    }

    @Test
    void eventsAreOrderedByFingerprintRegardlessOfInputOrder() {
        ChangeEvent a = event(ChangeKind.OPERATION_REMOVED, "/a");
        ChangeEvent b = event(ChangeKind.OPERATION_REMOVED, "/b");
        ChangeEvent c = event(ChangeKind.OPERATION_DEPRECATED, "/c");
        assertThat(set(a, b, c).events()).isEqualTo(set(c, b, a).events());
    }

    @Test
    void highestSeverityPicksTheWorstEvent() {
        ChangeSet mixed =
                set(
                        event(ChangeKind.OPERATION_ADDED, "/a"),
                        event(ChangeKind.OPERATION_DEPRECATED, "/b"),
                        event(ChangeKind.OPERATION_REMOVED, "/c"));
        assertThat(mixed.highestSeverity()).isEqualTo(Severity.BREAKING);
        assertThat(mixed.breakingEvents()).hasSize(1);
    }

    @Test
    void emptySetIsAdvisoryAndNeedsNoMigration() {
        ChangeSet empty = set();
        assertThat(empty.highestSeverity()).isEqualTo(Severity.ADVISORY);
        assertThat(empty.requiresMigration()).isFalse();
    }

    @Test
    void additiveOnlyChangesDoNotRequireMigration() {
        assertThat(set(event(ChangeKind.OPERATION_ADDED, "/a")).requiresMigration()).isFalse();
    }

    @Test
    void advisoryOnlyChangesDoNotRequireMigration() {
        ChangeEvent advisory =
                ChangeEvent.of(
                        "acme",
                        "1",
                        "2",
                        ChangeKind.ADVISORY_BREAKING,
                        SymbolRef.advisory("section 4"),
                        Map.of(),
                        "text only");
        assertThat(set(advisory).requiresMigration()).isFalse();
    }

    @Test
    void deprecationsRequireMigration() {
        assertThat(set(event(ChangeKind.OPERATION_DEPRECATED, "/a")).requiresMigration()).isTrue();
    }

    @Test
    void keyIsStableForTheSameVersionPair() {
        assertThat(ChangeSet.key("acme", "1", "2")).isEqualTo(set().key());
        assertThat(ChangeSet.key("acme", "1", "3")).isNotEqualTo(set().key());
    }

    @Test
    void severityOrderingMatchesImpact() {
        assertThat(Severity.BREAKING.atLeast(Severity.DEPRECATION)).isTrue();
        assertThat(Severity.ADDITIVE.atLeast(Severity.DEPRECATION)).isFalse();
        assertThat(Severity.ADVISORY.atLeast(Severity.ADVISORY)).isTrue();
    }

    @Test
    void notesDefaultToEmptyAndCopyInput() {
        MigrationNote note = new MigrationNote("title", "body", null);
        assertThat(note.url()).isEmpty();
        ChangeSet withNotes =
                new ChangeSet(
                        Ids.newId(),
                        "acme",
                        SourceKind.RELEASE_FEED,
                        "1",
                        "2",
                        null,
                        List.of(note),
                        Instant.EPOCH);
        assertThat(withNotes.notes()).containsExactly(note);
        assertThat(withNotes.events()).isEmpty();
    }
}
