package dev.rook.changes.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.Severity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OperationDiffTest {

    static OpenApiDocument spec(String paths) {
        return OpenApiLoader.load(
                "openapi: 3.0.0\ninfo: {title: t, version: '1'}\npaths:\n"
                        + paths
                        + "\ncomponents: {schemas: {}}\n");
    }

    static List<ChangeEvent> diff(String before, String after) {
        return OpenApiDiffer.diff("acme", "1", "2", spec(before), spec(after));
    }

    static Optional<ChangeEvent> find(List<ChangeEvent> events, ChangeKind kind, String subject) {
        return events.stream()
                .filter(e -> e.kind() == kind && e.subject().name().equals(subject))
                .findFirst();
    }

    @Test
    void identicalDocumentsProduceNoEvents() {
        OpenApiDocument doc = SpecFixtures.v1();
        assertThat(OpenApiDiffer.diff("acme", doc, doc)).isEmpty();
    }

    @Test
    void removedOperationIsBreaking() {
        List<ChangeEvent> events =
                diff(
                        "  /a:\n    get:\n      operationId: getA\n      responses: {'200': {description: ok}}\n",
                        "  /b:\n    get:\n      operationId: getB\n      responses: {'200': {description: ok}}\n");
        ChangeEvent removed = find(events, ChangeKind.OPERATION_REMOVED, "GET /a").orElseThrow();
        assertThat(removed.severity()).isEqualTo(Severity.BREAKING);
        assertThat(removed.attribute(Attr.REPLACEMENT)).isNull();
        assertThat(find(events, ChangeKind.OPERATION_ADDED, "GET /b")).isPresent();
    }

    @Test
    void movedOperationIsLinkedByOperationId() {
        List<ChangeEvent> events =
                diff(
                        "  /v1/charges/{id}:\n    get:\n      operationId: getCharge\n      responses: {'200': {description: ok}}\n",
                        "  /v1/payments/{id}:\n    get:\n      operationId: getCharge\n      responses: {'200': {description: ok}}\n");
        ChangeEvent removed =
                find(events, ChangeKind.OPERATION_REMOVED, "GET /v1/charges/{id}").orElseThrow();
        assertThat(removed.attribute(Attr.REPLACEMENT)).isEqualTo("GET /v1/payments/{id}");
        assertThat(find(events, ChangeKind.OPERATION_ADDED, "GET /v1/payments/{id}")).isEmpty();
    }

    @Test
    void operationIdsThatAreNotUniqueAreNotTreatedAsMoves() {
        List<ChangeEvent> events =
                diff(
                        "  /old:\n    get:\n      operationId: dup\n      responses: {'200': {description: ok}}\n",
                        "  /new1:\n    get:\n      operationId: dup\n      responses: {'200': {description: ok}}\n  /new2:\n    get:\n      operationId: dup\n      responses: {'200': {description: ok}}\n");
        ChangeEvent removed = find(events, ChangeKind.OPERATION_REMOVED, "GET /old").orElseThrow();
        assertThat(removed.attribute(Attr.REPLACEMENT)).isNull();
    }

    @Test
    void deprecationCarriesTheSunsetDate() {
        List<ChangeEvent> events =
                diff(
                        "  /a:\n    get:\n      operationId: getA\n      responses: {'200': {description: ok}}\n",
                        "  /a:\n    get:\n      operationId: getA\n      deprecated: true\n      x-sunset: '2027-01-01'\n      responses: {'200': {description: ok}}\n");
        ChangeEvent deprecated =
                find(events, ChangeKind.OPERATION_DEPRECATED, "GET /a").orElseThrow();
        assertThat(deprecated.severity()).isEqualTo(Severity.DEPRECATION);
        assertThat(deprecated.attribute(Attr.SUNSET)).isEqualTo("2027-01-01");
    }

    @Test
    void alreadyDeprecatedOperationsDoNotRepeat() {
        String both =
                "  /a:\n    get:\n      operationId: getA\n      deprecated: true\n      responses: {'200': {description: ok}}\n";
        assertThat(diff(both, both)).isEmpty();
    }

    @Test
    void newRequiredQueryParameterIsBreaking() {
        List<ChangeEvent> events =
                diff(
                        "  /a:\n    get:\n      operationId: getA\n      responses: {'200': {description: ok}}\n",
                        "  /a:\n    get:\n      operationId: getA\n      parameters:\n        - {name: region, in: query, required: true, schema: {type: string}}\n      responses: {'200': {description: ok}}\n");
        ChangeEvent added =
                find(events, ChangeKind.PARAMETER_REQUIRED_ADDED, "GET /a query:region")
                        .orElseThrow();
        assertThat(added.isBreaking()).isTrue();
    }

    @Test
    void newOptionalParameterIsAdditive() {
        List<ChangeEvent> events =
                diff(
                        "  /a:\n    get:\n      operationId: getA\n      responses: {'200': {description: ok}}\n",
                        "  /a:\n    get:\n      operationId: getA\n      parameters:\n        - {name: expand, in: query, schema: {type: boolean}}\n      responses: {'200': {description: ok}}\n");
        ChangeEvent added =
                find(events, ChangeKind.PARAMETER_OPTIONAL_ADDED, "GET /a query:expand")
                        .orElseThrow();
        assertThat(added.severity()).isEqualTo(Severity.ADDITIVE);
    }

    @Test
    void optionalParameterBecomingRequiredIsBreaking() {
        List<ChangeEvent> events =
                diff(
                        "  /a:\n    get:\n      operationId: getA\n      parameters:\n        - {name: q, in: query, schema: {type: string}}\n      responses: {'200': {description: ok}}\n",
                        "  /a:\n    get:\n      operationId: getA\n      parameters:\n        - {name: q, in: query, required: true, schema: {type: string}}\n      responses: {'200': {description: ok}}\n");
        assertThat(find(events, ChangeKind.PARAMETER_MADE_REQUIRED, "GET /a query:q")).isPresent();
    }

    @Test
    void parameterTypeChangeRecordsBothTypes() {
        List<ChangeEvent> events =
                diff(
                        "  /a:\n    get:\n      operationId: getA\n      parameters:\n        - {name: limit, in: query, schema: {type: integer}}\n      responses: {'200': {description: ok}}\n",
                        "  /a:\n    get:\n      operationId: getA\n      parameters:\n        - {name: limit, in: query, schema: {type: string}}\n      responses: {'200': {description: ok}}\n");
        ChangeEvent changed =
                find(events, ChangeKind.PARAMETER_TYPE_CHANGED, "GET /a query:limit").orElseThrow();
        assertThat(changed.attribute(Attr.FROM)).isEqualTo("integer");
        assertThat(changed.attribute(Attr.TO)).isEqualTo("string");
    }

    @Test
    void removedParameterWithASimilarlyNamedReplacementIsLinked() {
        List<ChangeEvent> events =
                diff(
                        "  /a:\n    get:\n      operationId: getA\n      parameters:\n        - {name: page_size, in: query, schema: {type: integer}}\n      responses: {'200': {description: ok}}\n",
                        "  /a:\n    get:\n      operationId: getA\n      parameters:\n        - {name: pageSize, in: query, schema: {type: integer}}\n      responses: {'200': {description: ok}}\n");
        ChangeEvent removed =
                find(events, ChangeKind.PARAMETER_REMOVED, "GET /a query:page_size").orElseThrow();
        assertThat(removed.attribute(Attr.REPLACEMENT)).isEqualTo("pageSize");
    }

    @Test
    void ambiguousReplacementsAreLeftUnlinked() {
        List<ChangeEvent> events = OpenApiDiffer.diff("acme", SpecFixtures.v1(), SpecFixtures.v2());
        ChangeEvent removed =
                find(events, ChangeKind.PARAMETER_REMOVED, "GET /v1/charges query:starting_after")
                        .orElseThrow();
        assertThat(removed.attribute(Attr.REPLACEMENT)).isNull();
    }

    @Test
    void requestBodyReferenceChangeIsATypeChange() {
        String body =
                "  /a:\n    post:\n      operationId: postA\n      requestBody:\n        required: true\n        content:\n          application/json:\n            schema: {$ref: '#/components/schemas/%s'}\n      responses: {'200': {description: ok}}\n";
        List<ChangeEvent> events = diff(body.formatted("Old"), body.formatted("New"));
        ChangeEvent changed =
                find(events, ChangeKind.PARAMETER_TYPE_CHANGED, "POST /a body:body").orElseThrow();
        assertThat(changed.attribute(Attr.FROM)).isEqualTo("ref:Old");
        assertThat(changed.attribute(Attr.TO)).isEqualTo("ref:New");
    }

    @Test
    void removedSuccessStatusIsBreakingButErrorStatusesAreIgnored() {
        List<ChangeEvent> events =
                diff(
                        "  /a:\n    get:\n      operationId: getA\n      responses: {'200': {description: ok}, '201': {description: made}, '404': {description: gone}}\n",
                        "  /a:\n    get:\n      operationId: getA\n      responses: {'200': {description: ok}}\n");
        assertThat(find(events, ChangeKind.RESPONSE_STATUS_REMOVED, "GET /a")).isPresent();
        assertThat(events).hasSize(1);
    }

    @Test
    void fullFixtureDiffFindsTheExpectedOperationChanges() {
        List<ChangeEvent> events = OpenApiDiffer.diff("acme", SpecFixtures.v1(), SpecFixtures.v2());
        assertThat(find(events, ChangeKind.OPERATION_REMOVED, "GET /v1/charges/{id}"))
                .get()
                .extracting(e -> e.attribute(Attr.REPLACEMENT))
                .isEqualTo("GET /v1/payments/{id}");
        assertThat(find(events, ChangeKind.OPERATION_ADDED, "POST /v1/payouts")).isPresent();
        assertThat(find(events, ChangeKind.OPERATION_DEPRECATED, "POST /v1/refunds")).isPresent();
        assertThat(
                        find(
                                events,
                                ChangeKind.PARAMETER_OPTIONAL_ADDED,
                                "GET /v1/charges query:cursor"))
                .isPresent();
    }

    @Test
    void eventsAreSortedBreakingFirstAndDeterministic() {
        List<ChangeEvent> first = OpenApiDiffer.diff("acme", SpecFixtures.v1(), SpecFixtures.v2());
        List<ChangeEvent> second = OpenApiDiffer.diff("acme", SpecFixtures.v1(), SpecFixtures.v2());
        assertThat(first).isEqualTo(second);
        assertThat(first.get(0).severity()).isEqualTo(Severity.BREAKING);
        for (int i = 1; i < first.size(); i++) {
            assertThat(first.get(i - 1).severity().compareTo(first.get(i).severity()))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void fingerprintsDependOnTheVersionPair() {
        OpenApiDocument a = SpecFixtures.v1();
        OpenApiDocument b = SpecFixtures.v2();
        List<ChangeEvent> one = OpenApiDiffer.diff("acme", "1", "2", a, b);
        List<ChangeEvent> two = OpenApiDiffer.diff("acme", "1", "3", a, b);
        assertThat(one)
                .extracting(ChangeEvent::fingerprint)
                .doesNotContainAnyElementsOf(two.stream().map(ChangeEvent::fingerprint).toList());
    }
}
