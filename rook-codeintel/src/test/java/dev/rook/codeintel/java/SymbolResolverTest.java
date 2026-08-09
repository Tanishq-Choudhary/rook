package dev.rook.codeintel.java;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.Usage;
import dev.rook.codeintel.java.Model.UsageKind;
import dev.rook.testkit.TestJars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolResolverTest {

    @TempDir static Path shared;

    static Path root;
    static Path sdkJar;
    static CodeIndex index;
    static SymbolResolver withJar;
    static SymbolResolver withoutJar;

    private static void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeAll
    static void setUp() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(
                "com.acme.pay.PaymentClient",
                "package com.acme.pay; public class PaymentClient { public PaymentClient(String key) {} public static PaymentClient shared() { return null; } public com.acme.pay.model.Charge charge(long amount) { return null; } public void refund(String id) {} public void close() {} public java.util.List<com.acme.pay.model.Charge> list() { return null; } }");
        sources.put(
                "com.acme.pay.model.Charge",
                "package com.acme.pay.model; public class Charge { public String getId() { return \"\"; } public Status getStatus() { return null; } }");
        sources.put(
                "com.acme.pay.model.Status",
                "package com.acme.pay.model; public enum Status { PENDING, FAILED }");
        sdkJar = TestJars.build(shared, "acme-sdk", sources);
        root = Files.createDirectories(shared.resolve("repo"));
        write(
                "src/main/java/com/shop/Fancy.java",
                "package com.shop; import com.acme.pay.PaymentClient; public class Fancy extends PaymentClient { public Fancy() { super(\"k\"); } public void own() {} }");
        write(
                "src/main/java/com/shop/Service.java",
                """
                package com.shop;

                import com.acme.pay.PaymentClient;
                import com.acme.pay.model.Charge;
                import com.acme.pay.model.Status;

                public class Service {

                    private final PaymentClient client = new PaymentClient("key");

                    Charge pay(long amount, Fancy fancy) {
                        Charge charge = client.charge(amount);
                        fancy.charge(amount);
                        fancy.own();
                        PaymentClient.shared().charge(1);
                        client.list();
                        if (charge.getStatus() == Status.FAILED) {
                            return null;
                        }
                        return charge;
                    }

                    String label(Status status) {
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

                    void unknown(Missing missing) {
                        missing.explode();
                    }
                }
                """);
        index = JavaIndexer.index(root);
        List<String> roots = List.of("src/main/java");
        withJar = SymbolResolver.create(index, roots, List.of(sdkJar));
        withoutJar = SymbolResolver.create(index, roots, List.of());
    }

    private static FileIndex service() {
        return index.file("src/main/java/com/shop/Service.java").orElseThrow();
    }

    private static List<Usage> find(UsageKind kind, String name) {
        return service().usages().stream()
                .filter(u -> u.kind() == kind && u.name().equals(name))
                .toList();
    }

    @Test
    void resolvesAMethodCallToItsDeclaringType() {
        Usage call =
                find(UsageKind.METHOD_CALL, "charge").stream()
                        .filter(u -> "client".equals(u.scope()))
                        .findFirst()
                        .orElseThrow();
        SymbolResolver.Resolved resolved = withJar.resolve(call).orElseThrow();
        assertThat(resolved.owner()).isEqualTo("com.acme.pay.PaymentClient");
        assertThat(resolved.member()).isEqualTo("charge");
        assertThat(resolved.signature()).isEqualTo("com.acme.pay.PaymentClient.charge(long)");
    }

    @Test
    void inheritedMethodsResolveToTheSuperclass() {
        Usage call =
                find(UsageKind.METHOD_CALL, "charge").stream()
                        .filter(u -> "fancy".equals(u.scope()))
                        .findFirst()
                        .orElseThrow();
        assertThat(withJar.resolve(call).orElseThrow().owner())
                .isEqualTo("com.acme.pay.PaymentClient");
    }

    @Test
    void methodsDeclaredInTheRepositoryResolveThroughSourceRoots() {
        Usage own = find(UsageKind.METHOD_CALL, "own").get(0);
        assertThat(withJar.resolve(own).orElseThrow().owner()).isEqualTo("com.shop.Fancy");
    }

    @Test
    void chainedCallsResolveThroughReturnTypes() {
        Usage chained =
                find(UsageKind.METHOD_CALL, "charge").stream()
                        .filter(u -> u.scope() != null && u.scope().contains("shared"))
                        .findFirst()
                        .orElseThrow();
        assertThat(chained.scopeType()).isNull();
        assertThat(withJar.resolve(chained).orElseThrow().owner())
                .isEqualTo("com.acme.pay.PaymentClient");
    }

    @Test
    void constructorsFieldsTypesAndReferencesResolve() {
        Usage constructor = find(UsageKind.CONSTRUCTOR_CALL, "PaymentClient").get(0);
        assertThat(withJar.resolve(constructor).orElseThrow().signature())
                .isEqualTo("com.acme.pay.PaymentClient.PaymentClient(java.lang.String)");
        Usage field = find(UsageKind.FIELD_ACCESS, "FAILED").get(0);
        assertThat(withJar.resolve(field).orElseThrow().owner())
                .isEqualTo("com.acme.pay.model.Status");
        Usage type = find(UsageKind.TYPE_USE, "Charge").get(0);
        assertThat(withJar.resolve(type).orElseThrow().owner())
                .isEqualTo("com.acme.pay.model.Charge");
        Usage reference = find(UsageKind.METHOD_REFERENCE, "close").get(0);
        assertThat(withJar.resolve(reference).orElseThrow().owner())
                .isEqualTo("com.acme.pay.PaymentClient");
    }

    @Test
    void switchLabelsResolveThroughTheSelectorType() {
        Usage label = find(UsageKind.SWITCH_LABEL, "FAILED").get(0);
        SymbolResolver.Resolved resolved = withJar.resolve(label).orElseThrow();
        assertThat(resolved.owner()).isEqualTo("com.acme.pay.model.Status");
        assertThat(resolved.member()).isEqualTo("FAILED");
    }

    @Test
    void withoutTheLibraryOnTheClasspathResolutionFailsQuietly() {
        Usage call =
                find(UsageKind.METHOD_CALL, "charge").stream()
                        .filter(u -> "client".equals(u.scope()))
                        .findFirst()
                        .orElseThrow();
        assertThat(withoutJar.resolve(call)).isEmpty();
    }

    @Test
    void unknownReceiversAreNotResolved() {
        Usage explode = find(UsageKind.METHOD_CALL, "explode").get(0);
        Optional<SymbolResolver.Resolved> resolved = withJar.resolve(explode);
        assertThat(resolved).isEmpty();
    }

    @Test
    void usagesOfOtherKindsAreIgnored() {
        Usage string = find(UsageKind.STRING_LITERAL, "failed").get(0);
        assertThat(withJar.resolve(string)).isEmpty();
    }
}
