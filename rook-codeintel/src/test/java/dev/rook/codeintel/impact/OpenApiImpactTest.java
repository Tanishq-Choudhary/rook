package dev.rook.codeintel.impact;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.JavaIndexer;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiImpactTest {

    @TempDir static Path root;

    static ImpactAnalyzer analyzer;

    private static void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeAll
    static void setUp() throws IOException {
        write(
                "src/main/java/com/shop/model/Charge.java",
                """
                package com.shop.model;

                import com.fasterxml.jackson.annotation.JsonProperty;

                public class Charge {
                    private String id;
                    private long amount;
                    @JsonProperty("customer_id")
                    private String customerId;
                    private String status;
                    private long created;

                    public String getId() { return id; }
                    public String getCustomerId() { return customerId; }
                    public void setCustomerId(String value) { customerId = value; }
                    public String getStatus() { return status; }
                    public long getCreated() { return created; }
                }
                """);
        write(
                "src/main/java/com/shop/model/ChargeStatus.java",
                "package com.shop.model; public enum ChargeStatus { PENDING, SUCCEEDED, FAILED }");
        write(
                "src/main/java/com/shop/model/CreateChargeRequest.java",
                """
                package com.shop.model;

                public class CreateChargeRequest {
                    private long amount;
                    private String currency;
                    public static Builder builder() { return new Builder(); }
                    public static class Builder {}
                }
                """);
        write(
                "src/main/java/com/shop/api/ChargeApi.java",
                """
                package com.shop.api;

                import com.shop.model.Charge;
                import com.shop.model.CreateChargeRequest;

                public interface ChargeApi {
                    @GET("/v1/charges/{id}")
                    Charge getCharge(@Path("id") String id);

                    @GET("/v1/charges")
                    Object listCharges(@Query("limit") Integer limit, @Query("starting_after") String startingAfter);

                    @POST("/v1/charges")
                    Charge createCharge(@Body CreateChargeRequest body);
                }
                """);
        write(
                "src/main/java/com/shop/PaymentService.java",
                """
                package com.shop;

                import com.shop.api.ChargeApi;
                import com.shop.model.Charge;
                import com.shop.model.ChargeStatus;
                import com.shop.model.CreateChargeRequest;

                public class PaymentService {

                    private final ChargeApi api;

                    PaymentService(ChargeApi api) {
                        this.api = api;
                    }

                    String owner(String id) {
                        Charge charge = api.getCharge(id);
                        return charge.getCustomerId();
                    }

                    boolean failed(Charge charge) {
                        return charge.getStatus().equals("failed") || ChargeStatus.FAILED.name().isEmpty();
                    }

                    String url(String id) {
                        return "/v1/charges/" + id + "?starting_after=x";
                    }

                    void create() {
                        CreateChargeRequest request = CreateChargeRequest.builder().build();
                        api.createCharge(request);
                    }

                    String cursor() {
                        return "starting_after";
                    }
                }
                """);
        write(
                "src/main/java/com/shop/Elsewhere.java",
                "package com.shop; class Elsewhere { String other() { return \"/v2/other\"; } String name() { return \"customer\"; } }");
        write(
                "src/test/resources/fixtures/charge.json",
                "{\n  \"id\": \"ch_1\",\n  \"customer_id\": \"cus_1\",\n  \"status\": \"failed\"\n}\n");
        CodeIndex index = JavaIndexer.index(root);
        analyzer = ImpactAnalyzer.create(index, null);
    }

    private static Impact only(ImpactReport report) {
        assertThat(report.impacts()).hasSize(1);
        return report.impacts().get(0);
    }

    private static ChangeEvent operation(
            ChangeKind kind, String verb, String path, String operationId) {
        return ChangeEvent.of(
                "acme",
                "1",
                "2",
                kind,
                SymbolRef.operation(verb, path),
                Map.of(Attr.OPERATION_ID, operationId),
                kind.name());
    }

    private static ChangeEvent field(ChangeKind kind, String schema, String field) {
        return ChangeEvent.of(
                "acme",
                "1",
                "2",
                kind,
                SymbolRef.schemaField(schema, field),
                Map.of(Attr.SCHEMA, schema, Attr.FIELD, field, Attr.DIRECTION, "response"),
                kind.name());
    }

    @Test
    void removedOperationFindsAnnotatedClientMethodsAndPathLiterals() {
        ChangeEvent event =
                operation(ChangeKind.OPERATION_REMOVED, "GET", "/v1/charges/{id}", "getCharge");
        Impact impact = only(analyzer.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::referenceKind)
                .contains("ANNOTATION", "ENDPOINT_PATH", "METHOD_DECLARATION", "METHOD_CALL");
        assertThat(impact.locations())
                .anyMatch(
                        l ->
                                l.referenceKind().equals("ANNOTATION")
                                        && l.file().endsWith("ChargeApi.java")
                                        && l.confidence() == Confidence.HEURISTIC);
        assertThat(impact.locations())
                .noneMatch(
                        l ->
                                l.snippet().contains("@POST(\"/v1/charges\")")
                                        || l.file().endsWith("Elsewhere.java"));
    }

    @Test
    void prefixLiteralsAreOnlyTextualMatches() {
        ChangeEvent event =
                operation(ChangeKind.OPERATION_REMOVED, "GET", "/v1/charges/{id}", "getCharge");
        Impact impact = only(analyzer.analyze(List.of(event)));
        ImpactLocation concatenated =
                impact.locations().stream()
                        .filter(l -> l.snippet().contains("\"/v1/charges/\" + id"))
                        .findFirst()
                        .orElseThrow();
        assertThat(concatenated.confidence()).isEqualTo(Confidence.TEXTUAL);
    }

    @Test
    void verbSpecificAnnotationsMustAgreeWithTheOperation() {
        ChangeEvent post =
                operation(ChangeKind.OPERATION_DEPRECATED, "POST", "/v1/charges", "createCharge");
        Impact impact = only(analyzer.analyze(List.of(post)));
        List<ImpactLocation> annotations =
                impact.locations().stream()
                        .filter(l -> l.referenceKind().equals("ANNOTATION"))
                        .toList();
        assertThat(annotations).hasSize(1);
        assertThat(annotations.get(0).snippet()).contains("POST");
    }

    @Test
    void removedQueryParameterFindsAnnotationsAndLiteralsInFilesThatCallTheOperation() {
        ChangeEvent event =
                ChangeEvent.of(
                        "acme",
                        "1",
                        "2",
                        ChangeKind.PARAMETER_REMOVED,
                        SymbolRef.parameter("GET /v1/charges", "query", "starting_after"),
                        Map.of(Attr.NAME, "starting_after", Attr.LOCATION, "query"),
                        "removed");
        Impact impact = only(analyzer.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::file)
                .contains("src/main/java/com/shop/api/ChargeApi.java");
        assertThat(impact.locations())
                .anyMatch(
                        l ->
                                l.referenceKind().equals("ANNOTATION")
                                        && l.confidence() == Confidence.HEURISTIC);
    }

    @Test
    void renamedFieldFindsTheDtoFieldItsAccessorsAndJsonNames() {
        ChangeEvent event = field(ChangeKind.FIELD_RENAMED, "Charge", "customer_id");
        Impact impact = only(analyzer.analyze(List.of(event)));
        ImpactLocation declaration =
                impact.locations().stream()
                        .filter(l -> l.referenceKind().equals("DTO_FIELD"))
                        .findFirst()
                        .orElseThrow();
        assertThat(declaration.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(declaration.resolvedTarget()).isEqualTo("com.shop.model.Charge#customerId");
        assertThat(impact.locations())
                .anyMatch(
                        l ->
                                l.referenceKind().equals("METHOD_CALL")
                                        && l.snippet().contains("charge.getCustomerId()")
                                        && l.confidence() == Confidence.HEURISTIC);
        assertThat(impact.locations())
                .anyMatch(
                        l ->
                                l.referenceKind().equals("RESOURCE")
                                        && l.file().endsWith("charge.json")
                                        && l.test());
        assertThat(impact.locations()).noneMatch(l -> l.file().endsWith("Elsewhere.java"));
    }

    @Test
    void removedEnumValueFindsConstantsUsagesAndStringLiterals() {
        ChangeEvent event =
                ChangeEvent.of(
                        "acme",
                        "1",
                        "2",
                        ChangeKind.ENUM_VALUE_REMOVED,
                        SymbolRef.enumValue("Charge.status", "failed"),
                        Map.of(Attr.VALUE, "failed"),
                        "removed");
        Impact impact = only(analyzer.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::referenceKind)
                .contains("ENUM_CONSTANT", "FIELD_ACCESS", "ENUM_LITERAL", "RESOURCE");
        assertThat(impact.locations())
                .anyMatch(
                        l ->
                                l.referenceKind().equals("ENUM_CONSTANT")
                                        && l.resolvedTarget()
                                                .equals("com.shop.model.ChargeStatus#FAILED"));
    }

    @Test
    void newRequiredRequestFieldPointsAtTheDtoAndItsConstructionSites() {
        ChangeEvent event =
                ChangeEvent.of(
                        "acme",
                        "1",
                        "2",
                        ChangeKind.REQUEST_FIELD_REQUIRED_ADDED,
                        SymbolRef.schemaField("CreateChargeRequest", "idempotency_key"),
                        Map.of(
                                Attr.SCHEMA,
                                "CreateChargeRequest",
                                Attr.FIELD,
                                "idempotency_key",
                                Attr.DIRECTION,
                                "request"),
                        "added");
        Impact impact = only(analyzer.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::referenceKind)
                .contains("DTO_TYPE", "METHOD_CALL");
        assertThat(impact.locations())
                .anyMatch(l -> l.snippet().contains("CreateChargeRequest.builder()"));
    }

    @Test
    void fieldsNobodyUsesAreUnmatched() {
        ChangeEvent event = field(ChangeKind.RESPONSE_FIELD_REMOVED, "Charge", "receipt_email");
        ImpactReport report = analyzer.analyze(List.of(event));
        assertThat(report.hasImpact()).isFalse();
        assertThat(report.unmatched()).hasSize(1);
    }

    @Test
    void operationsNobodyCallsAreUnmatched() {
        ChangeEvent event =
                operation(
                        ChangeKind.OPERATION_REMOVED,
                        "DELETE",
                        "/v1/unknown/{id}",
                        "deleteUnknown");
        assertThat(analyzer.analyze(List.of(event)).hasImpact()).isFalse();
    }
}
