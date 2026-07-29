package dev.rook.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.MigrationNote;
import dev.rook.core.change.SourceKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.core.id.Ids;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

class JsonTest {

    private static ChangeSet sample() {
        ChangeEvent removed =
                ChangeEvent.of(
                        "acme",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod("com.acme.Client", "charge", List.of("long")),
                        Map.of("replacement", "createCharge"),
                        "charge renamed to createCharge");
        return new ChangeSet(
                Ids.newId(),
                "acme",
                SourceKind.MAVEN,
                "1.0.0",
                "2.0.0",
                List.of(removed),
                List.of(new MigrationNote("Renames", "see guide", "https://example.test/guide")),
                Instant.parse("2026-08-03T10:00:00Z"));
    }

    @Test
    void changeSetSurvivesARoundTrip() {
        ChangeSet original = sample();
        ChangeSet copy = Json.read(Json.write(original), ChangeSet.class);
        assertThat(copy).isEqualTo(original);
    }

    @Test
    void instantsAreWrittenAsIsoStrings() {
        assertThat(Json.write(Map.of("at", Instant.parse("2026-08-03T10:00:00Z"))))
                .contains("2026-08-03T10:00:00Z");
    }

    @Test
    void mapKeysAreOrderedForStableOutput() {
        Map<String, Integer> unordered = new LinkedHashMap<>();
        unordered.put("b", 2);
        unordered.put("a", 1);
        unordered.put("c", 3);
        assertThat(Json.write(unordered)).isEqualTo("{\"a\":1,\"b\":2,\"c\":3}");
    }

    @Test
    void unknownPropertiesAreIgnoredOnRead() {
        MigrationNote note =
                Json.read(
                        "{\"title\":\"t\",\"body\":\"b\",\"url\":\"u\",\"extra\":1}",
                        MigrationNote.class);
        assertThat(note.title()).isEqualTo("t");
    }

    @Test
    void malformedJsonBecomesAnInvalidRequest() {
        assertThatThrownBy(() -> Json.read("{not json", MigrationNote.class))
                .isInstanceOfSatisfying(
                        RookException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID));
    }

    @Test
    void genericTypesCanBeRead() {
        Map<String, List<Integer>> parsed =
                Json.read("{\"a\":[1,2],\"b\":[]}", new TypeReference<>() {});
        assertThat(parsed).containsEntry("a", List.of(1, 2)).containsEntry("b", List.of());
    }

    @Test
    void treeParsesAndRejectsGarbage() {
        assertThat(Json.tree("{\"a\":{\"b\":1}}").get("a").get("b").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> Json.tree("[1,")).isInstanceOf(RookException.class);
    }

    @Test
    void convertMapsBetweenShapes() {
        MigrationNote note =
                Json.convert(Map.of("title", "t", "body", "b", "url", "u"), MigrationNote.class);
        assertThat(note.url()).isEqualTo("u");
    }

    @Test
    void prettyOutputSpansMultipleLines() {
        assertThat(Json.writePretty(Map.of("a", 1))).contains("\n");
    }
}
