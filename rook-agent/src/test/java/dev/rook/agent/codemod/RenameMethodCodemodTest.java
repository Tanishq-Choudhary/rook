package dev.rook.agent.codemod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenameMethodCodemodTest {

    private static final String SERVICE =
            """
            package com.shop;

            import com.acme.pay.PaymentClient;
            import java.util.function.LongFunction;

            class Service {
                PaymentClient client;
                Other other;

                void go() {
                    client.charge(5);
                    other.charge(7);
                    String id   =   client.charge(6);
                    LongFunction<String> f = client::charge;
                    helper().client.charge(8);
                }

                Service helper() {
                    return this;
                }
            }
            """;

    @TempDir Path temp;

    private CodemodFixtures fixtures;

    @BeforeEach
    void setUp() throws IOException {
        fixtures = new CodemodFixtures(temp);
        fixtures.write("src/main/java/com/shop/Service.java", SERVICE);
        fixtures.write(
                "src/main/java/com/shop/Other.java",
                "package com.shop;\nclass Other {\n    String charge(long amount) {\n        return \"\";\n    }\n}\n");
        fixtures.write("pom.xml", "<project/>\n");
    }

    private ChangeEvent event() {
        return ChangeEvent.of(
                "acme-sdk",
                "1.0.0",
                "2.0.0",
                ChangeKind.METHOD_RENAMED,
                SymbolRef.javaMethod("com.acme.pay.PaymentClient", "charge", List.of("long")),
                Map.of("newName", "createCharge"),
                "charge renamed");
    }

    private List<ImpactLocation> analyze(Path root) {
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(
                        root,
                        List.of(event()),
                        RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        return analysis.report().impacts().isEmpty()
                ? List.of()
                : analysis.report().impacts().get(0).locations();
    }

    @Test
    void everyAnalysedCallIsRenamedAndNothingElseChanges() throws IOException {
        Workspace workspace = fixtures.workspace();
        List<ImpactLocation> locations = analyze(fixtures.repo);
        assertThat(locations).isNotEmpty();
        CodemodRunner.Outcome outcome =
                CodemodRunner.run(
                        workspace, new RenameMethodCodemod("charge", "createCharge"), locations);
        assertThat(outcome.files()).isEqualTo(1);
        assertThat(outcome.edits()).isEqualTo(locations.size());
        assertThat(outcome.skipped()).isEmpty();
        String updated = fixtures.current("src/main/java/com/shop/Service.java");
        assertThat(updated)
                .contains("client.createCharge(5);")
                .contains("other.charge(7);")
                .contains("String id   =   client.createCharge(6);")
                .contains("client::createCharge")
                .contains("helper().client.createCharge(8);");
        assertThat(fixtures.current("src/main/java/com/shop/Other.java"))
                .contains("String charge(long amount)");
    }

    @Test
    void theDiffTouchesOnlyTheRenamedLines() {
        Workspace workspace = fixtures.workspace();
        List<ImpactLocation> locations = analyze(fixtures.repo);
        CodemodRunner.run(workspace, new RenameMethodCodemod("charge", "createCharge"), locations);
        long removed =
                workspace
                        .diff()
                        .lines()
                        .filter(l -> l.startsWith("-") && !l.startsWith("---"))
                        .count();
        long added =
                workspace
                        .diff()
                        .lines()
                        .filter(l -> l.startsWith("+") && !l.startsWith("+++"))
                        .count();
        assertThat(removed).isEqualTo(added).isEqualTo(locations.size());
    }

    @Test
    void afterTheRenameTheAnalysisFindsNothingLeftToMigrate() {
        Workspace workspace = fixtures.workspace();
        CodemodRunner.run(
                workspace,
                new RenameMethodCodemod("charge", "createCharge"),
                analyze(fixtures.repo));
        assertThat(analyze(workspace.root())).isEmpty();
    }

    @Test
    void runningTheSameCodemodTwiceDoesNothingTheSecondTime() {
        Workspace workspace = fixtures.workspace();
        List<ImpactLocation> locations = analyze(fixtures.repo);
        Codemod codemod = new RenameMethodCodemod("charge", "createCharge");
        CodemodRunner.run(workspace, codemod, locations);
        CodemodRunner.Outcome again = CodemodRunner.run(workspace, codemod, locations);
        assertThat(again.files()).isZero();
        assertThat(again.edits()).isZero();
        assertThat(again.skipped()).isEmpty();
    }

    @Test
    void aLocationThatDoesNotPointAtTheCallIsSkippedWithAReason() {
        Workspace workspace = fixtures.workspace();
        CodemodRunner.Outcome outcome =
                CodemodRunner.run(
                        workspace,
                        new RenameMethodCodemod("charge", "createCharge"),
                        List.of(
                                CodemodFixtures.location(
                                        "src/main/java/com/shop/Service.java",
                                        2,
                                        1,
                                        "METHOD_CALL")));
        assertThat(outcome.edits()).isZero();
        assertThat(outcome.skipped()).singleElement().asString().contains("no call of charge");
    }

    @Test
    void filesThatDoNotParseOrCannotBeReadAreSkipped() throws IOException {
        fixtures.write("src/main/java/com/shop/Broken.java", "class Broken { void x( }");
        fixtures.write(".env", "A=1\n");
        Workspace workspace = fixtures.workspace();
        CodemodRunner.Outcome outcome =
                CodemodRunner.run(
                        workspace,
                        new RenameMethodCodemod("charge", "createCharge"),
                        List.of(
                                CodemodFixtures.location(
                                        "src/main/java/com/shop/Broken.java", 1, 1, "METHOD_CALL"),
                                CodemodFixtures.location(".env", 1, 1, "METHOD_CALL"),
                                CodemodFixtures.location(
                                        "missing/File.java", 1, 1, "METHOD_CALL")));
        assertThat(outcome.edits()).isZero();
        assertThat(outcome.skipped()).hasSize(3);
        assertThat(outcome.skipped().get(0)).contains("does not parse");
    }

    @Test
    void onlyCallsAndMethodReferencesAreConsidered() {
        Workspace workspace = fixtures.workspace();
        CodemodRunner.Outcome outcome =
                CodemodRunner.run(
                        workspace,
                        new RenameMethodCodemod("charge", "createCharge"),
                        List.of(
                                CodemodFixtures.location(
                                        "src/main/java/com/shop/Service.java", 11, 9, "TYPE_USE")));
        assertThat(outcome.edits()).isZero();
        assertThat(outcome.skipped()).isEmpty();
    }

    @Test
    void theReportedConfidenceIsTheWeakestOneUsed() {
        Workspace workspace = fixtures.workspace();
        CodemodPlan plan =
                new RenameMethodCodemod("charge", "createCharge")
                        .plan(
                                workspace,
                                List.of(
                                        CodemodFixtures.location(
                                                "src/main/java/com/shop/Service.java",
                                                11,
                                                9,
                                                "METHOD_CALL",
                                                Confidence.RESOLVED),
                                        CodemodFixtures.location(
                                                "src/main/java/com/shop/Service.java",
                                                13,
                                                28,
                                                "METHOD_CALL",
                                                Confidence.HEURISTIC)));
        assertThat(plan.editCount()).isEqualTo(2);
        assertThat(plan.confidence()).isEqualTo(Confidence.HEURISTIC);
        assertThat(new RenameMethodCodemod("a", "b").name()).isEqualTo("rename-method a -> b");
    }

    @Test
    void invalidRenamesAreRefused() {
        assertThatThrownBy(() -> new RenameMethodCodemod("a", "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenameMethodCodemod("a", "1bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenameMethodCodemod("a", "b c"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
