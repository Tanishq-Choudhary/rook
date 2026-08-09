package dev.rook.codeintel.java;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.TypeInfo;
import dev.rook.codeintel.java.Model.Usage;
import dev.rook.codeintel.java.Model.UsageKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaIndexerTest {

    @TempDir Path root;

    private CodeIndex index;

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeEach
    void sampleRepository() throws IOException {
        write(
                "src/main/java/com/shop/PaymentService.java",
                """
                package com.shop;

                import com.acme.pay.PaymentClient;
                import com.acme.pay.model.Charge;
                import com.acme.pay.model.Status;
                import java.util.List;
                import static com.acme.pay.Limits.MAX;

                public class PaymentService {

                    private final PaymentClient client;
                    private static final String PATH = "/v1/charges";

                    public PaymentService(PaymentClient client) {
                        this.client = client;
                    }

                    public Charge pay(long amount, PaymentClient override) {
                        PaymentClient local = new PaymentClient("key");
                        var created = new PaymentClient("other");
                        Charge charge = client.charge(amount);
                        this.client.refund(charge.getId());
                        override.charge(amount);
                        local.charge(amount);
                        created.close();
                        PaymentClient.shared().charge(1);
                        List<Charge> all = client.list();
                        if (charge.getStatus() == Status.FAILED) {
                            return null;
                        }
                        return charge;
                    }

                    String describe(Status status) {
                        switch (status) {
                            case FAILED:
                                return "failed";
                            default:
                                return "other";
                        }
                    }

                    Runnable closer() {
                        return client::close;
                    }
                }
                """);
        write(
                "src/main/java/com/shop/Dto.java",
                """
                package com.shop;

                import com.fasterxml.jackson.annotation.JsonProperty;
                import org.springframework.web.bind.annotation.GetMapping;

                public record Dto(@JsonProperty("customer_id") String customerId, long amount) {

                    public enum Mode { LIVE, TEST }

                    public interface Listener extends Runnable {}

                    static class Inner {
                        @GetMapping("/v1/charges/{id}")
                        String path;
                        void go() {}
                    }
                }
                """);
        write(
                "src/test/java/com/shop/PaymentServiceTest.java",
                "package com.shop; class PaymentServiceTest {}");
        write("src/main/java/com/shop/Broken.java", "package com.shop; class Broken { void x( }");
        index = JavaIndexer.index(root);
    }

    private FileIndex service() {
        return index.file("src/main/java/com/shop/PaymentService.java").orElseThrow();
    }

    private List<Usage> usages(FileIndex file, UsageKind kind, String name) {
        return file.usages().stream()
                .filter(u -> u.kind() == kind && u.name().equals(name))
                .toList();
    }

    @Test
    void indexesDeclarationsWithQualifiedNames() {
        assertThat(index.types())
                .containsKeys(
                        "com.shop.PaymentService",
                        "com.shop.Dto",
                        "com.shop.Dto.Mode",
                        "com.shop.Dto.Listener",
                        "com.shop.Dto.Inner");
        TypeInfo serviceType = index.type("com.shop.PaymentService").orElseThrow();
        assertThat(serviceType.kind()).isEqualTo("CLASS");
        assertThat(serviceType.fields())
                .extracting(f -> f.name())
                .containsExactly("client", "PATH");
        assertThat(serviceType.methods())
                .extracting(m -> m.name())
                .contains("<init>", "pay", "describe", "closer");
        assertThat(index.type("com.shop.Dto").orElseThrow().kind()).isEqualTo("RECORD");
        assertThat(index.type("com.shop.Dto.Mode").orElseThrow().kind()).isEqualTo("ENUM");
        assertThat(index.type("com.shop.Dto.Listener").orElseThrow().kind()).isEqualTo("INTERFACE");
    }

    @Test
    void capturesRecordComponentsEnumConstantsAndAnnotationValues() {
        TypeInfo dto = index.type("com.shop.Dto").orElseThrow();
        assertThat(dto.fields()).extracting(f -> f.name()).containsExactly("customerId", "amount");
        assertThat(dto.fields().get(0).annotations()).containsExactly("JsonProperty");
        assertThat(dto.fields().get(0).annotationValues()).containsExactly("customer_id");
        TypeInfo mode = index.type("com.shop.Dto.Mode").orElseThrow();
        assertThat(mode.fields()).extracting(f -> f.name()).containsExactly("LIVE", "TEST");
        assertThat(mode.fields()).allMatch(f -> f.enumConstant());
        TypeInfo inner = index.type("com.shop.Dto.Inner").orElseThrow();
        assertThat(inner.fields().get(0).annotationValues()).containsExactly("/v1/charges/{id}");
    }

    @Test
    void recordsInterfaceSupertypes() {
        assertThat(index.type("com.shop.Dto.Listener").orElseThrow().superTypes())
                .containsExactly("Runnable");
    }

    @Test
    void readsImportsIncludingStaticOnes() {
        FileIndex file = service();
        assertThat(file.packageName()).isEqualTo("com.shop");
        assertThat(file.imports())
                .extracting(i -> i.name())
                .contains("com.acme.pay.PaymentClient", "java.util.List");
        assertThat(file.imports())
                .anyMatch(i -> i.isStatic() && i.name().equals("com.acme.pay.Limits.MAX"));
        assertThat(file.imports("com.acme.pay.PaymentClient")).isTrue();
        assertThat(file.imports("com.acme.pay.Missing")).isFalse();
    }

    @Test
    void inferredScopeTypesCoverFieldsParametersLocalsAndVar() {
        List<Usage> charges = usages(service(), UsageKind.METHOD_CALL, "charge");
        assertThat(charges)
                .extracting(Usage::scopeType)
                .containsExactlyInAnyOrder("PaymentClient", "PaymentClient", "PaymentClient", null);
        assertThat(charges)
                .filteredOn(u -> "client".equals(u.scope()))
                .singleElement()
                .satisfies(u -> assertThat(u.scopeType()).isEqualTo("PaymentClient"));
        assertThat(charges)
                .filteredOn(u -> "override".equals(u.scope()))
                .singleElement()
                .satisfies(u -> assertThat(u.scopeType()).isEqualTo("PaymentClient"));
        assertThat(charges)
                .filteredOn(u -> "local".equals(u.scope()))
                .singleElement()
                .satisfies(u -> assertThat(u.scopeType()).isEqualTo("PaymentClient"));
        assertThat(usages(service(), UsageKind.METHOD_CALL, "close"))
                .singleElement()
                .satisfies(u -> assertThat(u.scopeType()).isEqualTo("PaymentClient"));
        assertThat(usages(service(), UsageKind.METHOD_CALL, "refund"))
                .singleElement()
                .satisfies(u -> assertThat(u.scopeType()).isEqualTo("PaymentClient"));
    }

    @Test
    void staticCallsRecordTheTypeScope() {
        Usage shared = usages(service(), UsageKind.METHOD_CALL, "shared").get(0);
        assertThat(shared.scopeType()).isEqualTo("PaymentClient");
        Usage chained =
                usages(service(), UsageKind.METHOD_CALL, "charge").stream()
                        .filter(u -> u.scope() != null && u.scope().contains("shared"))
                        .findFirst()
                        .orElseThrow();
        assertThat(chained.scopeType()).isNull();
    }

    @Test
    void constructorCallsAndTypeUsesAreRecorded() {
        assertThat(usages(service(), UsageKind.CONSTRUCTOR_CALL, "PaymentClient")).hasSize(2);
        assertThat(usages(service(), UsageKind.TYPE_USE, "PaymentClient").size())
                .isGreaterThanOrEqualTo(3);
        assertThat(usages(service(), UsageKind.TYPE_USE, "Charge")).isNotEmpty();
        assertThat(usages(service(), UsageKind.TYPE_USE, "List")).isNotEmpty();
    }

    @Test
    void enumConstantsAreSeenThroughFieldAccessAndSwitchLabels() {
        Usage access = usages(service(), UsageKind.FIELD_ACCESS, "FAILED").get(0);
        assertThat(access.scope()).isEqualTo("Status");
        assertThat(access.scopeType()).isEqualTo("Status");
        assertThat(usages(service(), UsageKind.SWITCH_LABEL, "FAILED")).hasSize(1);
    }

    @Test
    void methodReferencesAreRecorded() {
        Usage reference = usages(service(), UsageKind.METHOD_REFERENCE, "close").get(0);
        assertThat(reference.scope()).isEqualTo("client");
        assertThat(reference.scopeType()).isEqualTo("PaymentClient");
    }

    @Test
    void annotationsAndStringsKeepTheirValues() {
        FileIndex dto = index.file("src/main/java/com/shop/Dto.java").orElseThrow();
        Usage mapping = usages(dto, UsageKind.ANNOTATION, "GetMapping").get(0);
        assertThat(mapping.values()).containsExactly("/v1/charges/{id}");
        assertThat(usages(dto, UsageKind.STRING_LITERAL, "customer_id")).hasSize(1);
        assertThat(usages(service(), UsageKind.STRING_LITERAL, "/v1/charges")).hasSize(1);
    }

    @Test
    void usagesCarryPositionsEnclosingMemberAndSnippet() {
        Usage call = usages(service(), UsageKind.METHOD_CALL, "refund").get(0);
        assertThat(call.enclosing()).isEqualTo("com.shop.PaymentService#pay");
        assertThat(call.snippet()).isEqualTo("this.client.refund(charge.getId());");
        assertThat(call.location().line()).isGreaterThan(10);
        assertThat(call.location().file()).isEqualTo("src/main/java/com/shop/PaymentService.java");
        Usage constructorUse =
                usages(service(), UsageKind.FIELD_ACCESS, "client").stream()
                        .findFirst()
                        .orElse(null);
        assertThat(constructorUse).isNotNull();
        assertThat(constructorUse.enclosing()).isEqualTo("com.shop.PaymentService#<init>");
    }

    @Test
    void unparseableFilesAreReportedAndDoNotStopIndexing() {
        assertThat(index.problems())
                .anyMatch(p -> p.startsWith("src/main/java/com/shop/Broken.java"));
        assertThat(index.file("src/main/java/com/shop/PaymentService.java")).isPresent();
    }

    @Test
    void testFilesAreMarked() {
        assertThat(
                        index.file("src/test/java/com/shop/PaymentServiceTest.java")
                                .orElseThrow()
                                .test())
                .isTrue();
        assertThat(service().test()).isFalse();
    }

    @Test
    void sourceRootsRestrictWhatIsIndexed() {
        CodeIndex restricted = JavaIndexer.index(root, List.of("src/test/java"));
        assertThat(restricted.files())
                .extracting(FileIndex::path)
                .containsExactly("src/test/java/com/shop/PaymentServiceTest.java");
    }

    @Test
    void typeLookupBySimpleName() {
        assertThat(index.typesBySimpleName("Mode"))
                .extracting(TypeInfo::qualifiedName)
                .containsExactly("com.shop.Dto.Mode");
        Optional<TypeInfo> none = index.type("com.nothing.Here");
        assertThat(none).isEmpty();
    }
}
