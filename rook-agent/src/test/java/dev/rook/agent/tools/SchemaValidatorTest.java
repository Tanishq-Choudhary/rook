package dev.rook.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SchemaValidatorTest {

    private final JsonNode schema =
            Schemas.object(
                    Map.of(
                            "path", Schemas.string("p", 1, 10),
                            "line", Schemas.integer("l", 1, 100),
                            "ratio", Schemas.number("r", 0, 1),
                            "flag", Schemas.bool("f"),
                            "mode", Schemas.enumOf("m", List.of("a", "b")),
                            "tags", Schemas.array("t", Schemas.string("tag", 1, 5), 2)),
                    List.of("path"));

    private List<String> check(Object value) {
        return SchemaValidator.validate(schema, Json.tree(value));
    }

    @Test
    void aValidObjectPasses() {
        assertThat(
                        check(
                                Map.of(
                                        "path",
                                        "a.txt",
                                        "line",
                                        5,
                                        "ratio",
                                        0.5,
                                        "flag",
                                        true,
                                        "mode",
                                        "a",
                                        "tags",
                                        List.of("x", "y"))))
                .isEmpty();
    }

    @Test
    void requiredPropertiesMustBePresent() {
        assertThat(check(Map.of("line", 1))).containsExactly("$.path is required");
    }

    @Test
    void unknownPropertiesAreRejectedWhenTheSchemaIsClosed() {
        assertThat(check(Map.of("path", "a", "extra", 1)))
                .containsExactly("$.extra is not an allowed property");
    }

    @Test
    void typesAreEnforced() {
        assertThat(check(Map.of("path", 5))).containsExactly("$.path must be a string");
        assertThat(check(Map.of("path", "a", "line", "3")))
                .containsExactly("$.line must be an integer");
        assertThat(check(Map.of("path", "a", "line", 1.5)))
                .containsExactly("$.line must be an integer");
        assertThat(check(Map.of("path", "a", "ratio", "x")))
                .containsExactly("$.ratio must be a number");
        assertThat(check(Map.of("path", "a", "flag", "yes")))
                .containsExactly("$.flag must be a boolean");
        assertThat(SchemaValidator.validate(schema, Json.tree(List.of())))
                .containsExactly("$ must be an object");
        assertThat(check(Map.of("path", "a", "tags", "x")))
                .containsExactly("$.tags must be an array");
    }

    @Test
    void lengthAndRangeLimitsAreEnforced() {
        assertThat(check(Map.of("path", "")))
                .containsExactly("$.path must have at least 1 characters");
        assertThat(check(Map.of("path", "x".repeat(11))))
                .containsExactly("$.path must have at most 10 characters");
        assertThat(check(Map.of("path", "a", "line", 0))).hasSize(1);
        assertThat(check(Map.of("path", "a", "line", 101))).hasSize(1);
        assertThat(check(Map.of("path", "a", "ratio", 2))).hasSize(1);
    }

    @Test
    void enumsAndArrayLimitsAreEnforced() {
        assertThat(check(Map.of("path", "a", "mode", "c"))).hasSize(1);
        assertThat(check(Map.of("path", "a", "tags", List.of("a", "b", "c")))).hasSize(1);
        assertThat(check(Map.of("path", "a", "tags", List.of("toolong"))))
                .containsExactly("$.tags[0] must have at most 5 characters");
    }

    @Test
    void patternsAreMatchedWithFind() {
        JsonNode pattern = Json.tree(Map.of("type", "string", "pattern", "^[a-z]+$"));
        assertThat(SchemaValidator.validate(pattern, Json.tree("abc"))).isEmpty();
        assertThat(SchemaValidator.validate(pattern, Json.tree("ab1"))).hasSize(1);
    }

    @Test
    void severalProblemsAreAllReported() {
        assertThat(check(Map.of("line", "x", "extra", 1))).hasSize(3);
    }

    @Test
    void unsupportedSchemasAreRefusedUpFront() {
        assertThatThrownBy(
                        () ->
                                SchemaValidator.requireSupported(
                                        Json.tree(Map.of("type", "string", "format", "uri"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format");
        assertThatThrownBy(
                        () -> SchemaValidator.requireSupported(Json.tree(Map.of("type", "null"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaValidator.requireSupported(Json.tree(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                SchemaValidator.requireSupported(
                                        Json.tree(Map.of("type", "string", "pattern", "["))))
                .isInstanceOf(IllegalArgumentException.class);
        SchemaValidator.requireSupported(schema);
    }
}
