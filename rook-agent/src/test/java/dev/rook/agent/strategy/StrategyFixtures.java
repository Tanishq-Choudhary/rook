package dev.rook.agent.strategy;

import dev.rook.agent.loop.AgentBudget;
import dev.rook.agent.loop.AgentListener;
import dev.rook.agent.loop.AgentLoop;
import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.loop.FinishTool;
import dev.rook.agent.loop.RunTestsTool;
import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.loop.TestRun;
import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.PriceTable;
import dev.rook.agent.safety.PromptSanitizer;
import dev.rook.agent.tools.ApplyEditTool;
import dev.rook.agent.tools.FindReferencesTool;
import dev.rook.agent.tools.ListImpactedTool;
import dev.rook.agent.tools.ReadFileTool;
import dev.rook.agent.tools.ReadMigrationNotesTool;
import dev.rook.agent.tools.SearchSymbolTool;
import dev.rook.agent.tools.ToolRegistry;
import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.codeintel.java.JavaIndexer;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

final class StrategyFixtures {

    static final String SERVICE = "src/main/java/com/shop/Service.java";
    static final String BILLING = "src/main/java/com/shop/Billing.java";

    static final ChangeEvent RENAME =
            ChangeEvent.of(
                    "acme-sdk",
                    "1.0.0",
                    "2.0.0",
                    ChangeKind.METHOD_RENAMED,
                    SymbolRef.javaMethod("com.acme.pay.PaymentClient", "charge", List.of("long")),
                    Map.of(Attr.NAME, "charge", Attr.REPLACEMENT, "createCharge"),
                    "charge renamed to createCharge");

    static final ChangeEvent REMOVAL =
            ChangeEvent.of(
                    "acme-sdk",
                    "1.0.0",
                    "2.0.0",
                    ChangeKind.METHOD_REMOVED,
                    SymbolRef.javaMethod("com.acme.pay.PaymentClient", "legacy", List.of()),
                    Map.of(Attr.OWNER, "com.acme.pay.PaymentClient"),
                    "legacy removed");

    final Path repo;
    final Workspace workspace;
    final AgentTask task;
    final ToolRegistry registry;

    StrategyFixtures(Path temp, List<ChangeEvent> events, SandboxPort sandbox) throws IOException {
        repo = Files.createDirectories(temp.resolve("repo"));
        write(
                SERVICE,
                "package com.shop;\n\nimport com.acme.pay.PaymentClient;\n\nclass Service {\n    PaymentClient client;\n    String token = \"ghp_"
                        + "c".repeat(36)
                        + "\";\n\n    void go() {\n        client.charge(5);\n        client.legacy();\n    }\n}\n");
        write(
                BILLING,
                "package com.shop;\n\nimport com.acme.pay.PaymentClient;\n\nclass Billing {\n    PaymentClient client;\n\n    void bill() {\n        client.charge(9);\n    }\n}\n");
        write("pom.xml", "<project/>\n");
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(
                        repo, events, RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        task =
                new AgentTask(
                        "acme/shop",
                        events,
                        analysis.report(),
                        List.of(),
                        JavaIndexer.index(repo),
                        "");
        workspace = Workspace.copyOf(repo, temp.resolve("work"), WorkspacePolicy.standard());
        SandboxPort toolSandbox = sandbox == null ? (root, scope) -> TestRun.passing(1) : sandbox;
        registry =
                new ToolRegistry(ToolRegistry.Limits.standard(), ToolRegistry.Listener.NOOP)
                        .register(new ReadFileTool())
                        .register(new ApplyEditTool())
                        .register(new ListImpactedTool())
                        .register(new SearchSymbolTool())
                        .register(new FindReferencesTool())
                        .register(new ReadMigrationNotesTool())
                        .register(new RunTestsTool(toolSandbox, AgentListener.NONE))
                        .register(new FinishTool());
    }

    private void write(String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    AgentLoop loop(ModelClient model, SandboxPort sandbox) {
        return new AgentLoop(
                model,
                PriceTable.empty(),
                registry,
                sandbox,
                Clock.systemUTC(),
                AgentBudget.standard(),
                PromptSanitizer.standard(8_000),
                AgentListener.NONE);
    }

    void close() {
        registry.close();
    }
}
