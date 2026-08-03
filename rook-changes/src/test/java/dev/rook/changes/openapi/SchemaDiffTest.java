package dev.rook.changes.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class SchemaDiffTest {

    enum Use {
        REQUEST,
        RESPONSE,
        NONE
    }

    static OpenApiDocument spec(String thing, Use use) {
        String paths =
                switch (use) {
                    case REQUEST ->
                            "  /x:\n    post:\n      operationId: postX\n      requestBody:\n        content:\n          application/json:\n            schema: {$ref: '#/components/schemas/Thing'}\n      responses: {'204': {description: ok}}\n";
                    case RESPONSE ->
                            "  /x:\n    get:\n      operationId: getX\n      responses:\n        '200':\n          description: ok\n          content:\n            application/json:\n              schema: {$ref: '#/components/schemas/Thing'}\n";
                    case NONE -> "  {}\n";
                };
        String body = use == Use.NONE ? "paths: {}\n" : "paths:\n" + paths;
        return OpenApiLoader.load(
                "openapi: 3.0.0\ninfo: {title: t, version: '1'}\n"
                        + body
                        + "components:\n  schemas:\n    Thing:\n"
                        + thing.indent(6));
    }

    static List<ChangeEvent> diff(String before, String after, Use use) {
        return OpenApiDiffer.diff("acme", "1", "2", spec(before, use), spec(after, use));
    }

    static Optional<ChangeEvent> find(List<ChangeEvent> events, ChangeKind kind, String subject) {
        return events.stream()
                .filter(e -> e.kind() == kind && e.subject().name().equals(subject))
                .findFirst();
    }

    @Test
    void fixtureRenameIsDetectedWithConfidence() {
        List<ChangeEvent> events =
                OpenApiDiffer.diff("acme", OpenApiLoaderTest.v1(), OpenApiLoaderTest.v2());
        ChangeEvent renamed =
                find(events, ChangeKind.FIELD_RENAMED, "Charge.customer_id").orElseThrow();
        assertThat(renamed.attribute(Attr.REPLACEMENT)).isEqualTo("customer");
        assertThat(Double.parseDouble(renamed.attribute(Attr.CONFIDENCE)))
                .isGreaterThanOrEqualTo(0.6);
        assertThat(find(events, ChangeKind.RESPONSE_FIELD_REMOVED, "Charge.customer_id")).isEmpty();
    }

    @Test
    void fixtureEnumChangesAreReported() {
        List<ChangeEvent> events =
                OpenApiDiffer.diff("acme", OpenApiLoaderTest.v1(), OpenApiLoaderTest.v2());
        assertThat(find(events, ChangeKind.ENUM_VALUE_REMOVED, "Charge.status=failed")).isPresent();
        assertThat(find(events, ChangeKind.ENUM_VALUE_ADDED, "Charge.status=declined")).isPresent();
        assertThat(find(events, ChangeKind.ENUM_VALUE_ADDED, "Charge.status=refunded")).isPresent();
        assertThat(find(events, ChangeKind.ENUM_VALUE_REMOVED, "CreateChargeRequest.currency=gbp"))
                .isPresent();
    }

    @Test
    void fixtureTypeAndRequirementChangesAreReported() {
        List<ChangeEvent> events =
                OpenApiDiffer.diff("acme", OpenApiLoaderTest.v1(), OpenApiLoaderTest.v2());
        ChangeEvent created =
                find(events, ChangeKind.RESPONSE_FIELD_TYPE_CHANGED, "Charge.created")
                        .orElseThrow();
        assertThat(created.attribute(Attr.FROM)).isEqualTo("integer:int64");
        assertThat(created.attribute(Attr.TO)).isEqualTo("string:date-time");
        assertThat(find(events, ChangeKind.RESPONSE_FIELD_ADDED, "Charge.receipt_url")).isPresent();
        assertThat(
                        find(
                                events,
                                ChangeKind.REQUEST_FIELD_REQUIRED_ADDED,
                                "CreateChargeRequest.idempotency_key"))
                .isPresent();
        assertThat(
                        find(
                                events,
                                ChangeKind.REQUEST_FIELD_MADE_REQUIRED,
                                "CreateChargeRequest.source"))
                .isPresent();
    }

    @Test
    void unchangedSchemasStaySilent() {
        List<ChangeEvent> events =
                OpenApiDiffer.diff("acme", OpenApiLoaderTest.v1(), OpenApiLoaderTest.v2());
        assertThat(events)
                .noneMatch(e -> e.subject().name().startsWith("Customer."))
                .noneMatch(e -> e.subject().name().startsWith("Error."));
    }

    @Test
    void removedFieldInAResponseSchemaIsAResponseBreak() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nproperties:\n  a: {type: string}\n  b: {type: string}\n",
                        "type: object\nproperties:\n  a: {type: string}\n",
                        Use.RESPONSE);
        ChangeEvent removed =
                find(events, ChangeKind.RESPONSE_FIELD_REMOVED, "Thing.b").orElseThrow();
        assertThat(removed.severity()).isEqualTo(Severity.BREAKING);
        assertThat(removed.attribute(Attr.DIRECTION)).isEqualTo("response");
    }

    @Test
    void removedFieldInARequestSchemaIsARequestBreak() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nproperties:\n  a: {type: string}\n  b: {type: string}\n",
                        "type: object\nproperties:\n  a: {type: string}\n",
                        Use.REQUEST);
        assertThat(find(events, ChangeKind.REQUEST_FIELD_REMOVED, "Thing.b")).isPresent();
    }

    @Test
    void addedRequiredRequestFieldIsBreakingButOptionalIsAdditive() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nproperties:\n  a: {type: string}\n",
                        "type: object\nrequired: [c]\nproperties:\n  a: {type: string}\n  b: {type: string}\n  c: {type: string}\n",
                        Use.REQUEST);
        assertThat(find(events, ChangeKind.REQUEST_FIELD_REQUIRED_ADDED, "Thing.c"))
                .get()
                .extracting(ChangeEvent::severity)
                .isEqualTo(Severity.BREAKING);
        assertThat(find(events, ChangeKind.REQUEST_FIELD_OPTIONAL_ADDED, "Thing.b"))
                .get()
                .extracting(ChangeEvent::severity)
                .isEqualTo(Severity.ADDITIVE);
    }

    @Test
    void responseFieldBecomingOptionalOrNullableIsBreaking() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nrequired: [a, b]\nproperties:\n  a: {type: string}\n  b: {type: string}\n",
                        "type: object\nrequired: [a]\nproperties:\n  a: {type: string, nullable: true}\n  b: {type: string}\n",
                        Use.RESPONSE);
        assertThat(find(events, ChangeKind.RESPONSE_FIELD_MADE_OPTIONAL, "Thing.a")).isPresent();
        assertThat(find(events, ChangeKind.RESPONSE_FIELD_MADE_OPTIONAL, "Thing.b")).isPresent();
    }

    @Test
    void requestFieldBecomingOptionalIsNotReported() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nrequired: [a]\nproperties:\n  a: {type: string}\n",
                        "type: object\nproperties:\n  a: {type: string}\n",
                        Use.REQUEST);
        assertThat(events).isEmpty();
    }

    @Test
    void nestedInlineObjectFieldsUseDottedPaths() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nproperties:\n  billing:\n    type: object\n    properties:\n      line1: {type: string}\n      zip: {type: string}\n",
                        "type: object\nproperties:\n  billing:\n    type: object\n    properties:\n      line1: {type: string}\n",
                        Use.RESPONSE);
        assertThat(find(events, ChangeKind.RESPONSE_FIELD_REMOVED, "Thing.billing.zip"))
                .isPresent();
    }

    @Test
    void arrayItemObjectsUseBracketPaths() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nproperties:\n  lines:\n    type: array\n    items:\n      type: object\n      properties:\n        sku: {type: string}\n        qty: {type: integer}\n",
                        "type: object\nproperties:\n  lines:\n    type: array\n    items:\n      type: object\n      properties:\n        sku: {type: string}\n",
                        Use.RESPONSE);
        assertThat(find(events, ChangeKind.RESPONSE_FIELD_REMOVED, "Thing.lines[].qty"))
                .isPresent();
    }

    @Test
    void deprecatedFieldIsReported() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nproperties:\n  a: {type: string}\n",
                        "type: object\nproperties:\n  a: {type: string, deprecated: true}\n",
                        Use.RESPONSE);
        assertThat(find(events, ChangeKind.FIELD_DEPRECATED, "Thing.a"))
                .get()
                .extracting(ChangeEvent::severity)
                .isEqualTo(Severity.DEPRECATION);
    }

    @Test
    void unreferencedSchemasAreTreatedAsBothDirections() {
        List<ChangeEvent> events =
                diff(
                        "type: object\nproperties:\n  a: {type: string}\n  b: {type: string}\n",
                        "type: object\nproperties:\n  a: {type: string}\n",
                        Use.NONE);
        ChangeEvent removed =
                find(events, ChangeKind.RESPONSE_FIELD_REMOVED, "Thing.b").orElseThrow();
        assertThat(removed.attribute(Attr.DIRECTION)).isEqualTo("both");
    }

    @Test
    void enumValueRenamesAreLinkedWhenOnlyOnePairRemains() {
        List<ChangeEvent> events =
                diff(
                        "type: string\nenum: [pending, done]\n",
                        "type: string\nenum: [pending, completed]\n",
                        Use.RESPONSE);
        ChangeEvent removed =
                find(events, ChangeKind.ENUM_VALUE_REMOVED, "Thing=done").orElseThrow();
        assertThat(removed.attribute(Attr.REPLACEMENT)).isEqualTo("completed");
    }

    @Test
    void inlineBodySchemasAreComparedUnderASyntheticName() {
        String op =
                "  /x:\n    post:\n      operationId: postX\n      requestBody:\n        content:\n          application/json:\n            schema:\n              type: object\n              properties:\n%s      responses: {'204': {description: ok}}\n";
        OpenApiDocument before =
                OpenApiLoader.load(
                        "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n"
                                + op.formatted(
                                        "                a: {type: string}\n                b: {type: string}\n"));
        OpenApiDocument after =
                OpenApiLoader.load(
                        "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n"
                                + op.formatted("                a: {type: string}\n"));
        List<ChangeEvent> events = OpenApiDiffer.diff("acme", "1", "2", before, after);
        assertThat(find(events, ChangeKind.REQUEST_FIELD_REMOVED, "POST /x request.b")).isPresent();
    }

    @Property
    void diffingADocumentWithItselfIsEmpty(@ForAll("fieldSets") List<String> fields) {
        OpenApiDocument doc = spec(schemaFor(fields), Use.RESPONSE);
        assertThat(OpenApiDiffer.diff("acme", doc, doc)).isEmpty();
    }

    @Property
    void addingAnOptionalResponseFieldIsOneAdditiveEvent(@ForAll("fieldSets") List<String> fields) {
        String added = "zz_new_field";
        List<String> extended = new ArrayList<>(fields);
        extended.add(added);
        List<ChangeEvent> events =
                OpenApiDiffer.diff(
                        "acme",
                        "1",
                        "2",
                        spec(schemaFor(fields), Use.RESPONSE),
                        spec(schemaFor(extended), Use.RESPONSE));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo(ChangeKind.RESPONSE_FIELD_ADDED);
        assertThat(events.get(0).severity()).isEqualTo(Severity.ADDITIVE);
    }

    @Property
    void removingOneResponseFieldIsOneBreakingEvent(@ForAll("fieldSets") List<String> fields) {
        Assume.that(!fields.isEmpty());
        List<String> reduced = fields.subList(1, fields.size());
        List<ChangeEvent> events =
                OpenApiDiffer.diff(
                        "acme",
                        "1",
                        "2",
                        spec(schemaFor(fields), Use.RESPONSE),
                        spec(schemaFor(reduced), Use.RESPONSE));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).severity()).isEqualTo(Severity.BREAKING);
    }

    @Provide
    Arbitrary<List<String>> fieldSets() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(3)
                .ofMaxLength(8)
                .map(s -> "f_" + s)
                .list()
                .uniqueElements()
                .ofMaxSize(6);
    }

    private static String schemaFor(List<String> fields) {
        StringBuilder sb = new StringBuilder("type: object\nproperties:\n");
        if (fields.isEmpty()) {
            sb.setLength(0);
            sb.append("type: object\nproperties: {}\n");
        }
        for (String field : fields) {
            sb.append("  ").append(field).append(": {type: string}\n");
        }
        return sb.toString();
    }
}
