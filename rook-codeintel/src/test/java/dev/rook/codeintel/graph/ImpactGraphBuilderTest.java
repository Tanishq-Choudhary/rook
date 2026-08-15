package dev.rook.codeintel.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.codeintel.graph.ImpactGraph.Edge;
import dev.rook.codeintel.graph.ImpactGraph.Node;
import dev.rook.codeintel.impact.ImpactAnalyzer;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.JavaIndexer;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.core.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImpactGraphBuilderTest {

    @TempDir static Path root;

    static CodeIndex index;
    static ImpactReport report;

    private static void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeAll
    static void setUp() throws IOException {
        write(
                "src/main/java/com/shop/Service.java",
                "package com.shop; import com.acme.pay.PaymentClient; public class Service { PaymentClient client; public void pay() { client.charge(5); } }");
        write(
                "src/main/java/com/shop/Controller.java",
                "package com.shop; public class Controller { Service service; public void handle() { service.pay(); } }");
        write(
                "src/main/java/com/shop/Scheduler.java",
                "package com.shop; public class Scheduler { Controller controller; public void nightly() { controller.handle(); } }");
        write(
                "src/main/java/com/shop/Standalone.java",
                "package com.shop; import com.acme.pay.PaymentClient; public class Standalone { PaymentClient c; void once() { c.charge(1); } }");
        index = JavaIndexer.index(root);
        ChangeEvent event =
                ChangeEvent.of(
                        "acme-sdk",
                        "1",
                        "2",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod(
                                "com.acme.pay.PaymentClient", "charge", List.of("long")),
                        Map.of(),
                        "charge renamed");
        report = ImpactAnalyzer.create(index, null).analyze(List.of(event));
    }

    private static List<String> ids(ImpactGraph graph, String kind) {
        return graph.nodes().stream().filter(n -> n.kind().equals(kind)).map(Node::id).toList();
    }

    @Test
    void changesConnectToTheSymbolsAndFilesTheyTouch() {
        ImpactGraph graph = new ImpactGraphBuilder(index, 0).build(report);
        assertThat(ids(graph, "CHANGE")).hasSize(1);
        assertThat(ids(graph, "SYMBOL"))
                .containsExactlyInAnyOrder(
                        "symbol:com.shop.Service#pay", "symbol:com.shop.Standalone#once");
        assertThat(ids(graph, "FILE"))
                .containsExactlyInAnyOrder(
                        "file:src/main/java/com/shop/Service.java",
                        "file:src/main/java/com/shop/Standalone.java");
        assertThat(graph.edges())
                .anyMatch(
                        e ->
                                e.kind().startsWith("touches:")
                                        && e.to().equals("symbol:com.shop.Service#pay"))
                .anyMatch(
                        e ->
                                e.kind().equals("declared_in")
                                        && e.from().equals("symbol:com.shop.Service#pay"));
    }

    @Test
    void callersAreAddedUpToTheConfiguredDepth() {
        ImpactGraph shallow = new ImpactGraphBuilder(index, 1).build(report);
        assertThat(ids(shallow, "SYMBOL"))
                .contains("symbol:com.shop.Controller#handle")
                .doesNotContain("symbol:com.shop.Scheduler#nightly");
        ImpactGraph deep = new ImpactGraphBuilder(index, 2).build(report);
        assertThat(ids(deep, "SYMBOL"))
                .contains("symbol:com.shop.Controller#handle", "symbol:com.shop.Scheduler#nightly");
        assertThat(deep.edges())
                .contains(
                        new Edge(
                                "symbol:com.shop.Service#pay",
                                "symbol:com.shop.Controller#handle",
                                "called_by"))
                .contains(
                        new Edge(
                                "symbol:com.shop.Controller#handle",
                                "symbol:com.shop.Scheduler#nightly",
                                "called_by"));
    }

    @Test
    void depthZeroAddsNoCallers() {
        ImpactGraph graph = new ImpactGraphBuilder(index, 0).build(report);
        assertThat(graph.edges()).noneMatch(e -> e.kind().equals("called_by"));
    }

    @Test
    void emptyReportsGiveAnEmptyGraph() {
        ImpactReport none = ImpactAnalyzer.create(index, null).analyze(List.of());
        assertThat(new ImpactGraphBuilder(index, 2).build(none).isEmpty()).isTrue();
    }

    @Test
    void outputIsDeterministicAndSerializable() {
        ImpactGraph first = new ImpactGraphBuilder(index, 2).build(report);
        ImpactGraph second = new ImpactGraphBuilder(index, 2).build(report);
        assertThat(first).isEqualTo(second);
        assertThat(Json.read(Json.write(first), ImpactGraph.class)).isEqualTo(first);
        assertThat(first.truncated()).isFalse();
    }

    @Test
    void largeGraphsAreCappedAndMarkedTruncated() throws IOException {
        Path big = Files.createDirectories(root.resolve("big"));
        for (int i = 0; i < ImpactGraphBuilder.MAX_NODES; i++) {
            Path file = big.resolve("src/main/java/p/C" + i + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    "package p; import com.acme.pay.PaymentClient; class C"
                            + i
                            + " { PaymentClient c; void m() { c.charge(1); } }");
        }
        CodeIndex bigIndex = JavaIndexer.index(big);
        ChangeEvent event =
                ChangeEvent.of(
                        "acme-sdk",
                        "1",
                        "2",
                        ChangeKind.METHOD_REMOVED,
                        SymbolRef.javaMethod(
                                "com.acme.pay.PaymentClient", "charge", List.of("long")),
                        Map.of(),
                        "removed");
        ImpactReport bigReport = ImpactAnalyzer.create(bigIndex, null).analyze(List.of(event));
        ImpactGraph graph = new ImpactGraphBuilder(bigIndex, 1).build(bigReport);
        assertThat(graph.nodes()).hasSize(ImpactGraphBuilder.MAX_NODES);
        assertThat(graph.truncated()).isTrue();
    }
}
