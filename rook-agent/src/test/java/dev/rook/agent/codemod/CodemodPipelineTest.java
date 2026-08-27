package dev.rook.agent.codemod;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.core.change.Attr;
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

class CodemodPipelineTest {

    @TempDir Path temp;

    private CodemodFixtures fixtures;

    @BeforeEach
    void setUp() throws IOException {
        fixtures = new CodemodFixtures(temp);
        fixtures.write(
                "src/main/java/com/shop/Service.java",
                "package com.shop;\n\nimport com.acme.pay.PaymentClient;\n\nclass Service {\n    PaymentClient client;\n\n    void go() {\n        client.charge(5);\n        client.legacy();\n    }\n}\n");
    }

    private static List<ChangeEvent> events() {
        return List.of(
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod(
                                "com.acme.pay.PaymentClient", "charge", List.of("long")),
                        Map.of(Attr.NAME, "charge", Attr.REPLACEMENT, "createCharge"),
                        "charge renamed"),
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_REMOVED,
                        SymbolRef.javaMethod("com.acme.pay.PaymentClient", "legacy", List.of()),
                        Map.of(Attr.OWNER, "com.acme.pay.PaymentClient"),
                        "legacy removed"));
    }

    @Test
    void automatedFixesApplyAndTheRestIsHandedOver() throws IOException {
        List<ChangeEvent> events = events();
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(
                        fixtures.repo,
                        events,
                        RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        Workspace workspace = fixtures.workspace();
        CodemodPipeline.Result result = CodemodPipeline.run(workspace, events, analysis.report());
        assertThat(result.outcomes()).hasSize(1);
        assertThat(result.edits()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
        assertThat(result.remaining()).hasSize(1);
        assertThat(result.remaining().get(0).line()).isEqualTo(10);
        assertThat(fixtures.current("src/main/java/com/shop/Service.java"))
                .contains("client.createCharge(5);")
                .contains("client.legacy();");
    }

    @Test
    void whenEverythingHasACodemodNothingRemains() {
        List<ChangeEvent> onlyRename = List.of(events().get(0));
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(
                        fixtures.repo,
                        onlyRename,
                        RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        CodemodPipeline.Result result =
                CodemodPipeline.run(fixtures.workspace(), onlyRename, analysis.report());
        assertThat(result.complete()).isTrue();
        assertThat(result.edits()).isEqualTo(1);
    }

    @Test
    void anEmptyReportDoesNothing() {
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(
                        fixtures.repo,
                        List.of(),
                        RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        CodemodPipeline.Result result =
                CodemodPipeline.run(fixtures.workspace(), List.of(), analysis.report());
        assertThat(result.outcomes()).isEmpty();
        assertThat(result.complete()).isTrue();
        assertThat(result.edits()).isZero();
    }
}
