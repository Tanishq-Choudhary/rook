package dev.rook.agent.loop;

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
import dev.rook.core.change.MigrationNote;
import dev.rook.core.change.SymbolRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

final class AgentFixtures {

    static final String SERVICE_PATH = "src/main/java/com/shop/Service.java";

    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class FakeSandbox implements SandboxPort {

        private final Deque<TestRun> results = new ArrayDeque<>();
        private TestRun last = TestRun.passing(5);
        final List<String> scopes = new ArrayList<>();

        FakeSandbox then(TestRun... runs) {
            results.addAll(List.of(runs));
            return this;
        }

        @Override
        public TestRun run(Path workspaceRoot, String scope) {
            scopes.add(scope);
            if (!results.isEmpty()) {
                last = results.poll();
            }
            return last;
        }
    }

    final Workspace workspace;
    final AgentTask task;
    final MutableClock clock = new MutableClock();
    final ToolRegistry registry;
    final List<Object> events = new ArrayList<>();

    AgentFixtures(Path temp, FakeSandbox sandbox) throws IOException {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        write(
                repo,
                SERVICE_PATH,
                "package com.shop;\n\nimport com.acme.pay.PaymentClient;\n\nclass Service {\n    PaymentClient client;\n    String token = \"ghp_"
                        + "b".repeat(36)
                        + "\";\n\n    void go() {\n        client.charge(5);\n    }\n}\n");
        write(repo, ".github/workflows/ci.yml", "name: ci\n");
        write(repo, "pom.xml", "<project/>\n");
        ChangeEvent event =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RENAMED,
                        SymbolRef.javaMethod(
                                "com.acme.pay.PaymentClient", "charge", List.of("long")),
                        Map.of(Attr.NAME, "charge", Attr.REPLACEMENT, "createCharge"),
                        "charge renamed to createCharge");
        RepoAnalyzer.Analysis analysis =
                RepoAnalyzer.analyze(
                        repo,
                        List.of(event),
                        RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        task =
                new AgentTask(
                        "acme/shop",
                        List.of(event),
                        analysis.report(),
                        List.of(
                                new MigrationNote(
                                        "Guide", "Use createCharge.", "https://example.test")),
                        JavaIndexer.index(repo),
                        "");
        workspace = Workspace.copyOf(repo, temp.resolve("work"), WorkspacePolicy.standard());
        SandboxPort toolSandbox = sandbox == null ? (root, scope) -> TestRun.passing(1) : sandbox;
        AgentListener recorder =
                new AgentListener() {
                    @Override
                    public void testsRun(TestRun run) {
                        events.add(run);
                    }
                };
        registry =
                new ToolRegistry(ToolRegistry.Limits.standard(), ToolRegistry.Listener.NOOP)
                        .register(new ReadFileTool())
                        .register(new ApplyEditTool())
                        .register(new ListImpactedTool())
                        .register(new SearchSymbolTool())
                        .register(new FindReferencesTool())
                        .register(new ReadMigrationNotesTool())
                        .register(new RunTestsTool(toolSandbox, recorder))
                        .register(new FinishTool());
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    AgentLoop loop(
            ModelClient model,
            SandboxPort sandbox,
            AgentBudget budget,
            PriceTable prices,
            AgentListener listener) {
        return new AgentLoop(
                model,
                prices,
                registry,
                sandbox,
                clock,
                budget,
                PromptSanitizer.standard(8_000),
                listener);
    }

    AgentResult run(ModelClient model, SandboxPort sandbox, AgentBudget budget) {
        return loop(model, sandbox, budget, PriceTable.empty(), AgentListener.NONE)
                .run(task, workspace);
    }

    void close() {
        registry.close();
    }
}
