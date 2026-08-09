package dev.rook.codeintel.impact;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.JavaIndexer;
import dev.rook.codeintel.java.SymbolResolver;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.Severity;
import dev.rook.core.change.SymbolRef;
import dev.rook.testkit.TestJars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaImpactTest {

    @TempDir static Path shared;

    static Path root;
    static CodeIndex index;
    static ImpactAnalyzer resolved;
    static ImpactAnalyzer unresolved;

    private static void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeAll
    static void setUp() throws IOException {
        Map<String, String> sdk = new LinkedHashMap<>();
        sdk.put(
                "com.acme.pay.PaymentClient",
                "package com.acme.pay; public class PaymentClient { public PaymentClient(String key) {} public static PaymentClient shared() { return null; } public String charge(long amount) { return \"\"; } public String charge(long amount, String note) { return \"\"; } public void refund(String id) {} public void close() {} }");
        sdk.put(
                "com.acme.pay.model.Status",
                "package com.acme.pay.model; public enum Status { PENDING, FAILED }");
        sdk.put(
                "com.acme.pay.model.Charge",
                "package com.acme.pay.model; public class Charge { public Status getStatus() { return null; } }");
        Path jar = TestJars.build(shared, "acme-sdk", sdk);
        root = Files.createDirectories(shared.resolve("repo"));
        write(
                "src/main/java/com/shop/Service.java",
                """
                package com.shop;

                import com.acme.pay.PaymentClient;
                import com.acme.pay.model.Charge;
                import com.acme.pay.model.Status;

                public class Service {

                    private final PaymentClient client = new PaymentClient("key");

                    void pay(long amount, Fancy fancy, com.other.Client other) {
                        client.charge(amount);
                        client.charge(amount, "note");
                        fancy.charge(amount);
                        other.charge(amount);
                        PaymentClient.shared().charge(amount);
                        client.refund("r1");
                    }

                    void inspect(Charge charge) {
                        if (charge.getStatus() == Status.FAILED) {
                            return;
                        }
                    }

                    String label(Status status) {
                        switch (status) {
                            case FAILED:
                                return "failed";
                            default:
                                return "ok";
                        }
                    }

                    Runnable closer() {
                        return client::close;
                    }
                }
                """);
        write(
                "src/main/java/com/shop/Fancy.java",
                "package com.shop; import com.acme.pay.PaymentClient; public class Fancy extends PaymentClient { public Fancy() { super(\"k\"); } @Override public String charge(long amount) { return \"x\"; } }");
        write(
                "src/main/java/com/other/Client.java",
                "package com.other; public class Client { public String charge(long amount) { return \"\"; } public static final String FAILED = \"f\"; }");
        write(
                "src/main/java/com/shop/Unrelated.java",
                "package com.shop; import com.other.Client; class Unrelated { void run(Client c) { c.charge(1); String s = Client.FAILED; } }");
        write(
                "src/test/java/com/shop/ServiceTest.java",
                "package com.shop; import com.acme.pay.PaymentClient; class ServiceTest { void t() { new PaymentClient(\"t\").close(); } }");
        index = JavaIndexer.index(root);
        List<String> roots = List.of("src/main/java", "src/test/java");
        resolved = ImpactAnalyzer.create(index, SymbolResolver.create(index, roots, List.of(jar)));
        unresolved = ImpactAnalyzer.create(index, null);
    }

    private static ChangeEvent method(
            ChangeKind kind, String owner, String name, List<String> params) {
        return ChangeEvent.of(
                "acme-sdk",
                "1",
                "2",
                kind,
                SymbolRef.javaMethod(owner, name, params),
                Map.of(),
                kind.name());
    }

    private static Impact only(ImpactReport report) {
        assertThat(report.impacts()).hasSize(1);
        return report.impacts().get(0);
    }

    private static List<Integer> lines(Impact impact, String file) {
        return impact.locations().stream()
                .filter(l -> l.file().endsWith(file))
                .map(ImpactLocation::line)
                .toList();
    }

    @Test
    void renamedMethodIsFoundThroughResolutionAndInheritance() {
        ChangeEvent event =
                method(
                        ChangeKind.METHOD_RENAMED,
                        "com.acme.pay.PaymentClient",
                        "charge",
                        List.of("long"));
        Impact impact = only(resolved.analyze(List.of(event)));
        List<String> snippets = impact.locations().stream().map(ImpactLocation::snippet).toList();
        assertThat(snippets)
                .contains(
                        "client.charge(amount);",
                        "fancy.charge(amount);",
                        "PaymentClient.shared().charge(amount);");
        assertThat(snippets)
                .doesNotContain("other.charge(amount);", "client.charge(amount, \"note\");");
        assertThat(impact.locations().stream().filter(l -> !l.referenceKind().equals("OVERRIDE")))
                .allMatch(l -> l.confidence() == Confidence.RESOLVED);
        assertThat(impact.locations()).noneMatch(l -> l.file().contains("Unrelated"));
    }

    @Test
    void overridesInRepositorySubclassesAreReported() {
        ChangeEvent event =
                method(
                        ChangeKind.METHOD_REMOVED,
                        "com.acme.pay.PaymentClient",
                        "charge",
                        List.of("long"));
        Impact impact = only(resolved.analyze(List.of(event)));
        assertThat(impact.locations())
                .anyMatch(
                        l ->
                                l.referenceKind().equals("OVERRIDE")
                                        && l.file().endsWith("Fancy.java"));
    }

    @Test
    void methodReferencesAndTestUsagesAreIncluded() {
        ChangeEvent event =
                method(ChangeKind.METHOD_REMOVED, "com.acme.pay.PaymentClient", "close", List.of());
        Impact impact = only(resolved.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::referenceKind)
                .contains("METHOD_REFERENCE", "METHOD_CALL");
        assertThat(impact.locations()).anyMatch(ImpactLocation::test);
        assertThat(impact.affectsMainCode()).isTrue();
    }

    @Test
    void constructorSignatureChangesFindObjectCreations() {
        ChangeEvent event =
                method(
                        ChangeKind.METHOD_PARAMETERS_CHANGED,
                        "com.acme.pay.PaymentClient",
                        "<init>",
                        List.of("java.lang.String"));
        Impact impact = only(resolved.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::referenceKind)
                .containsOnly("CONSTRUCTOR_CALL");
        assertThat(impact.locations()).hasSize(2);
        assertThat(impact.locations()).allMatch(l -> l.confidence() == Confidence.RESOLVED);
    }

    @Test
    void removedClassFindsImportsTypeUsesAndSubclasses() {
        ChangeEvent event =
                ChangeEvent.of(
                        "acme-sdk",
                        "1",
                        "2",
                        ChangeKind.CLASS_REMOVED,
                        SymbolRef.javaClass("com.acme.pay.PaymentClient"),
                        Map.of(),
                        "removed");
        Impact impact = only(resolved.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::referenceKind)
                .contains("IMPORT", "TYPE_USE", "CONSTRUCTOR_CALL", "EXTENDS");
        assertThat(impact.locations())
                .extracting(ImpactLocation::file)
                .contains(
                        "src/main/java/com/shop/Service.java",
                        "src/main/java/com/shop/Fancy.java",
                        "src/test/java/com/shop/ServiceTest.java");
        assertThat(impact.locations()).noneMatch(l -> l.file().contains("Unrelated"));
    }

    @Test
    void removedEnumConstantFindsAccessesAndSwitchLabels() {
        ChangeEvent event =
                ChangeEvent.of(
                        "acme-sdk",
                        "1",
                        "2",
                        ChangeKind.JAVA_FIELD_REMOVED,
                        SymbolRef.javaField("com.acme.pay.model.Status", "FAILED"),
                        Map.of(Attr.ENUM_CONSTANT, "true"),
                        "removed");
        Impact impact = only(resolved.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::referenceKind)
                .containsExactlyInAnyOrder("FIELD_ACCESS", "SWITCH_LABEL");
        assertThat(impact.locations()).allMatch(l -> l.confidence() == Confidence.RESOLVED);
        assertThat(impact.locations()).noneMatch(l -> l.file().contains("Unrelated"));
    }

    @Test
    void withoutResolutionHeuristicsStillAvoidTheObviousFalsePositives() {
        ChangeEvent event =
                method(
                        ChangeKind.METHOD_RENAMED,
                        "com.acme.pay.PaymentClient",
                        "charge",
                        List.of("long"));
        Impact impact = only(unresolved.analyze(List.of(event)));
        assertThat(impact.locations())
                .extracting(ImpactLocation::snippet)
                .contains("client.charge(amount);", "fancy.charge(amount);");
        assertThat(impact.locations())
                .extracting(ImpactLocation::snippet)
                .doesNotContain("other.charge(amount);");
        assertThat(impact.locations()).noneMatch(l -> l.file().contains("Unrelated"));
        assertThat(impact.locations().stream().map(ImpactLocation::confidence))
                .doesNotContain(Confidence.RESOLVED);
        ImpactLocation chained =
                impact.locations().stream()
                        .filter(l -> l.snippet().contains("shared()"))
                        .findFirst()
                        .orElseThrow();
        assertThat(chained.confidence()).isEqualTo(Confidence.TEXTUAL);
    }

    @Test
    void eventsWithoutUsagesAreReportedAsUnmatched() {
        ChangeEvent event =
                method(
                        ChangeKind.METHOD_REMOVED,
                        "com.acme.pay.PaymentClient",
                        "neverCalled",
                        List.of());
        ImpactReport report = resolved.analyze(List.of(event));
        assertThat(report.hasImpact()).isFalse();
        assertThat(report.unmatched()).hasSize(1);
        assertThat(report.stats().eventsAnalyzed()).isEqualTo(1);
        assertThat(report.stats().eventsWithImpact()).isZero();
    }

    @Test
    void additiveAndAdvisoryEventsAreNotAnalyzed() {
        ChangeEvent added =
                method(
                        ChangeKind.METHOD_ADDED,
                        "com.acme.pay.PaymentClient",
                        "charge",
                        List.of("long"));
        ChangeEvent advisory =
                ChangeEvent.of(
                        "acme-sdk",
                        "1",
                        "2",
                        ChangeKind.ADVISORY_BREAKING,
                        SymbolRef.advisory("x"),
                        Map.of(),
                        "note");
        ImpactReport report = resolved.analyze(List.of(added, advisory));
        assertThat(report.stats().eventsAnalyzed()).isZero();
    }

    @Test
    void statsSummariseLocationsFilesAndConfidence() {
        ChangeEvent rename =
                method(
                        ChangeKind.METHOD_RENAMED,
                        "com.acme.pay.PaymentClient",
                        "charge",
                        List.of("long"));
        ChangeEvent removed =
                method(ChangeKind.METHOD_REMOVED, "com.acme.pay.PaymentClient", "close", List.of());
        ImpactReport report = resolved.analyze(List.of(rename, removed));
        assertThat(report.stats().eventsWithImpact()).isEqualTo(2);
        assertThat(report.stats().locations())
                .isEqualTo(report.impacts().stream().mapToInt(i -> i.locations().size()).sum());
        assertThat(report.stats().affectedFiles()).isEqualTo(report.affectedFiles().size());
        assertThat(report.stats().byConfidence().get(Confidence.RESOLVED)).isPositive();
        assertThat(report.impacts().get(0).severity()).isEqualTo(Severity.BREAKING);
    }

    @Test
    void impactsKeepLocationsSortedByFileAndLine() {
        ChangeEvent event =
                method(
                        ChangeKind.METHOD_REMOVED,
                        "com.acme.pay.PaymentClient",
                        "charge",
                        List.of("long"));
        Impact impact = only(resolved.analyze(List.of(event)));
        List<String> keys =
                impact.locations().stream()
                        .map(l -> l.file() + ":" + String.format("%05d", l.line()))
                        .toList();
        assertThat(keys).isSorted();
        assertThat(lines(impact, "Service.java")).isNotEmpty();
    }
}
