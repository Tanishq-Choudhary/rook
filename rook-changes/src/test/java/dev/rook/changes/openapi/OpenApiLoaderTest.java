package dev.rook.changes.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import org.junit.jupiter.api.Test;

class OpenApiLoaderTest {

    @Test
    void readsTitleAndVersion() {
        OpenApiDocument doc = SpecFixtures.v1();
        assertThat(doc.title()).isEqualTo("Acme Payments");
        assertThat(doc.version()).isEqualTo("1.4.0");
    }

    @Test
    void keysOperationsByMethodAndPath() {
        assertThat(SpecFixtures.v1().operations())
                .containsKeys(
                        "POST /v1/charges",
                        "GET /v1/charges",
                        "GET /v1/charges/{id}",
                        "POST /v1/customers",
                        "POST /v1/refunds");
    }

    @Test
    void requestAndResponseSchemasAreReferences() {
        ApiOperation create = SpecFixtures.v1().operations().get("POST /v1/charges");
        assertThat(create.operationId()).isEqualTo("createCharge");
        assertThat(create.requestBody().ref()).isEqualTo("CreateChargeRequest");
        assertThat(create.requestBodyRequired()).isTrue();
        assertThat(create.responses().get("201").ref()).isEqualTo("Charge");
        assertThat(create.responses().get("400").ref()).isEqualTo("Error");
    }

    @Test
    void pathParametersAreAlwaysRequired() {
        ApiOperation get = SpecFixtures.v1().operations().get("GET /v1/charges/{id}");
        ApiParameter id = get.parametersByKey().get("path:id");
        assertThat(id.required()).isTrue();
        assertThat(id.schema().type()).isEqualTo("string");
    }

    @Test
    void queryParametersAreOptionalByDefault() {
        ApiOperation list = SpecFixtures.v1().operations().get("GET /v1/charges");
        assertThat(list.parametersByKey().get("query:limit").required()).isFalse();
        assertThat(list.parametersByKey().get("query:limit").schema().type()).isEqualTo("integer");
    }

    @Test
    void componentSchemaFieldsRequiredAndEnums() {
        ApiSchema charge = SpecFixtures.v1().schema("Charge");
        assertThat(charge.properties())
                .containsKeys("id", "amount", "currency", "status", "customer_id");
        assertThat(charge.required()).containsExactly("amount", "currency", "id", "status");
        assertThat(charge.properties().get("status").enumValues())
                .containsExactly("pending", "succeeded", "failed");
        assertThat(charge.properties().get("created").typeLabel()).isEqualTo("integer:int64");
    }

    @Test
    void arrayItemsKeepTheirReference() {
        ApiSchema data = SpecFixtures.v1().schema("ChargeList").properties().get("data");
        assertThat(data.type()).isEqualTo("array");
        assertThat(data.items().ref()).isEqualTo("Charge");
        assertThat(data.typeLabel()).isEqualTo("array[ref:Charge]");
    }

    @Test
    void deprecatedOperationsCarryTheSunsetExtension() {
        ApiOperation refund = SpecFixtures.v2().operations().get("POST /v1/refunds");
        assertThat(refund.deprecated()).isTrue();
        assertThat(refund.sunset()).isEqualTo("2027-03-01");
        assertThat(SpecFixtures.v1().operations().get("POST /v1/refunds").deprecated()).isFalse();
    }

    @Test
    void nullableIsReadFromBothSpecStyles() {
        String spec =
                """
                openapi: 3.1.0
                info: {title: t, version: '1'}
                paths: {}
                components:
                  schemas:
                    A:
                      type: object
                      properties:
                        old_style:
                          type: string
                          nullable: true
                        new_style:
                          type: [string, 'null']
                        plain:
                          type: string
                """;
        ApiSchema a = OpenApiLoader.load(spec).schema("A");
        assertThat(a.properties().get("old_style").nullable()).isTrue();
        assertThat(a.properties().get("new_style").nullable()).isTrue();
        assertThat(a.properties().get("new_style").type()).isEqualTo("string");
        assertThat(a.properties().get("plain").nullable()).isFalse();
    }

    @Test
    void allOfMergesPropertiesAndRequiredFromReferences() {
        String spec =
                """
                openapi: 3.0.0
                info: {title: t, version: '1'}
                paths: {}
                components:
                  schemas:
                    Base:
                      type: object
                      required: [id]
                      properties:
                        id: {type: string}
                    Child:
                      allOf:
                        - $ref: '#/components/schemas/Base'
                        - type: object
                          required: [name]
                          properties:
                            name: {type: string}
                """;
        ApiSchema child = OpenApiLoader.load(spec).schema("Child");
        assertThat(child.properties()).containsKeys("id", "name");
        assertThat(child.required()).containsExactlyInAnyOrder("id", "name");
        assertThat(child.type()).isEqualTo("object");
    }

    @Test
    void singleMemberAllOfCollapsesToTheReference() {
        String spec =
                """
                openapi: 3.0.0
                info: {title: t, version: '1'}
                paths: {}
                components:
                  schemas:
                    Base: {type: object, properties: {id: {type: string}}}
                    Wrapper:
                      type: object
                      properties:
                        inner:
                          description: wrapped
                          allOf:
                            - $ref: '#/components/schemas/Base'
                """;
        ApiSchema wrapper = OpenApiLoader.load(spec).schema("Wrapper");
        assertThat(wrapper.properties().get("inner").ref()).isEqualTo("Base");
    }

    @Test
    void oneOfVariantsContributeOptionalProperties() {
        String spec =
                """
                openapi: 3.0.0
                info: {title: t, version: '1'}
                paths: {}
                components:
                  schemas:
                    Cat: {type: object, required: [lives], properties: {lives: {type: integer}}}
                    Dog: {type: object, required: [bark], properties: {bark: {type: string}}}
                    Pet:
                      oneOf:
                        - $ref: '#/components/schemas/Cat'
                        - $ref: '#/components/schemas/Dog'
                """;
        ApiSchema pet = OpenApiLoader.load(spec).schema("Pet");
        assertThat(pet.properties()).containsKeys("lives", "bark");
        assertThat(pet.required()).isEmpty();
    }

    @Test
    void selfReferencingAllOfDoesNotLoopForever() {
        String spec =
                """
                openapi: 3.0.0
                info: {title: t, version: '1'}
                paths: {}
                components:
                  schemas:
                    Node:
                      allOf:
                        - $ref: '#/components/schemas/Node'
                        - type: object
                          properties:
                            value: {type: string}
                """;
        assertThat(OpenApiLoader.load(spec).schema("Node").properties()).containsKey("value");
    }

    @Test
    void referencedParametersAndPathLevelParametersAreResolved() {
        String spec =
                """
                openapi: 3.0.0
                info: {title: t, version: '1'}
                paths:
                  /things/{id}:
                    parameters:
                      - $ref: '#/components/parameters/Id'
                    get:
                      operationId: getThing
                      parameters:
                        - name: expand
                          in: query
                          schema: {type: boolean}
                      responses:
                        '204': {description: none}
                components:
                  parameters:
                    Id:
                      name: id
                      in: path
                      required: true
                      schema: {type: string}
                """;
        ApiOperation op = OpenApiLoader.load(spec).operations().get("GET /things/{id}");
        assertThat(op.parametersByKey()).containsKeys("path:id", "query:expand");
        assertThat(op.responses().get("204").isEmpty()).isTrue();
    }

    @Test
    void parseJsonDocumentsToo() {
        String json =
                "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"j\",\"version\":\"9\"},\"paths\":{\"/a\":{\"get\":{\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";
        OpenApiDocument doc = OpenApiLoader.load(json);
        assertThat(doc.version()).isEqualTo("9");
        assertThat(doc.operations()).containsKey("GET /a");
    }

    @Test
    void rejectsSwaggerTwoDocuments() {
        assertThatThrownBy(
                        () -> OpenApiLoader.load("swagger: '2.0'\ninfo: {title: a, version: '1'}"))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("OpenAPI 3");
    }

    @Test
    void rejectsGarbageInput() {
        assertThatThrownBy(() -> OpenApiLoader.load(": : :\n  - ["))
                .isInstanceOf(RookException.class);
        assertThatThrownBy(() -> OpenApiLoader.load("- just\n- a list"))
                .isInstanceOf(RookException.class);
    }

    @Test
    void operationIdFallsBackToMethodAndPath() {
        String spec =
                """
                openapi: 3.0.0
                info: {title: t, version: '1'}
                paths:
                  /x:
                    get:
                      responses:
                        '200': {description: ok}
                """;
        assertThat(OpenApiLoader.load(spec).operations().get("GET /x").operationId())
                .isEqualTo("GET /x");
    }
}
