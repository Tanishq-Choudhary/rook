package dev.rook.agent.strategy;

import static dev.rook.agent.strategy.StrategyFixtures.BILLING;
import static dev.rook.agent.strategy.StrategyFixtures.REMOVAL;
import static dev.rook.agent.strategy.StrategyFixtures.RENAME;
import static dev.rook.agent.strategy.StrategyFixtures.SERVICE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.loop.AgentBudget;
import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.loop.TestRun;
import dev.rook.agent.model.ModelPrice;
import dev.rook.agent.model.ModelResponse;
import dev.rook.agent.model.PriceTable;
import dev.rook.agent.model.ScriptedModelClient;
import dev.rook.agent.safety.PromptSanitizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrategyTest {

    @TempDir Path temp;

    private StrategyFixtures fixtures;

    private StrategyFixtures fixtures(ChangeEventSet events, SandboxPort sandbox)
            throws IOException {
        fixtures = new StrategyFixtures(temp, events.events(), sandbox);
        return fixtures;
    }

    private record ChangeEventSet(List<dev.rook.core.change.ChangeEvent> events) {}

    private static ChangeEventSet renameOnly() {
        return new ChangeEventSet(List.of(RENAME));
    }

    private static ChangeEventSet renameAndRemoval() {
        return new ChangeEventSet(List.of(RENAME, REMOVAL));
    }

    @AfterEach
    void tearDown() {
        if (fixtures != null) {
            fixtures.close();
        }
    }

    private String current(String path) throws IOException {
        return Files.readString(fixtures.workspace.root().resolve(path));
    }

    private static ModelResponse call(String name, Map<String, ?> arguments) {
        return ScriptedModelClient.toolCall(name, arguments);
    }

    @Test
    void codemodOnlyFixesWhatItCanAndListsTheRest() throws IOException {
        fixtures(renameAndRemoval(), null);
        StrategyOutcome outcome = new CodemodOnlyStrategy().run(fixtures.task, fixtures.workspace);
        assertThat(outcome.strategy()).isEqualTo("codemod-only");
        assertThat(outcome.status()).isEqualTo("PARTIAL");
        assertThat(outcome.hasPatch()).isTrue();
        assertThat(outcome.changedPaths()).containsExactly(BILLING, SERVICE);
        assertThat(outcome.unresolved()).singleElement().asString().contains(SERVICE + ":11");
        assertThat(outcome.costMicros()).isZero();
        assertThat(outcome.usage().totalTokens()).isZero();
        assertThat(outcome.verified()).isFalse();
        assertThat(current(SERVICE))
                .contains("client.createCharge(5);")
                .contains("client.legacy();");
    }

    @Test
    void codemodOnlyReportsCompleteWhenNothingIsLeft() throws IOException {
        fixtures(renameOnly(), null);
        StrategyOutcome outcome = new CodemodOnlyStrategy().run(fixtures.task, fixtures.workspace);
        assertThat(outcome.status()).isEqualTo("COMPLETE");
        assertThat(outcome.unresolved()).isEmpty();
    }

    private static String fileReply(String path, String content) {
        return "<file path=\"" + path + "\">\n" + content + "</file>";
    }

    private LlmOnlyStrategy llm(ScriptedModelClient model, AgentBudget budget, PriceTable prices) {
        return new LlmOnlyStrategy(model, prices, budget, PromptSanitizer.standard(8_000));
    }

    @Test
    void llmOnlyRewritesEachAffectedFileFromTheReply() throws IOException {
        fixtures(renameOnly(), null);
        String service = current(SERVICE).replace("client.charge(5);", "client.createCharge(5);");
        String billing = current(BILLING).replace("client.charge(9);", "client.createCharge(9);");
        ScriptedModelClient model =
                ScriptedModelClient.replying(
                        ScriptedModelClient.text(fileReply(BILLING, billing)),
                        ScriptedModelClient.text(fileReply(SERVICE, service)));
        StrategyOutcome outcome =
                llm(model, AgentBudget.standard(), PriceTable.empty())
                        .run(fixtures.task, fixtures.workspace);
        assertThat(outcome.strategy()).isEqualTo("llm-only");
        assertThat(outcome.status()).isEqualTo("COMPLETE");
        assertThat(outcome.changedPaths()).containsExactlyInAnyOrder(SERVICE, BILLING);
        assertThat(outcome.usage().totalTokens()).isEqualTo(40);
        assertThat(outcome.trace()).hasSize(2).noneMatch(step -> step.error());
        assertThat(current(SERVICE)).contains("createCharge(5)");
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void llmOnlyPromptsAreFencedAndRedacted() throws IOException {
        fixtures(renameOnly(), null);
        ScriptedModelClient model =
                ScriptedModelClient.replying(
                        ScriptedModelClient.text("UNCHANGED"),
                        ScriptedModelClient.text("UNCHANGED"));
        llm(model, AgentBudget.standard(), PriceTable.empty())
                .run(fixtures.task, fixtures.workspace);
        String prompt = model.requests().get(1).messages().get(0).text();
        assertThat(prompt).contains("<untrusted-content").contains("[REDACTED:github-token]");
        assertThat(prompt).doesNotContain("c".repeat(36));
        assertThat(model.requests().get(0).system()).contains("untrusted-content");
    }

    @Test
    void anUnchangedReplyLeavesTheFileAlone() throws IOException {
        fixtures(renameOnly(), null);
        ScriptedModelClient model =
                ScriptedModelClient.replying(
                        ScriptedModelClient.text("UNCHANGED"),
                        ScriptedModelClient.text("UNCHANGED"));
        StrategyOutcome outcome =
                llm(model, AgentBudget.standard(), PriceTable.empty())
                        .run(fixtures.task, fixtures.workspace);
        assertThat(outcome.hasPatch()).isFalse();
        assertThat(outcome.unresolved()).isEmpty();
    }

    @Test
    void repliesWithTheWrongPathOrNoFileAreReportedAsUnresolved() throws IOException {
        fixtures(renameOnly(), null);
        ScriptedModelClient model =
                ScriptedModelClient.replying(
                        ScriptedModelClient.text(fileReply("other/Path.java", "class X {}\n")),
                        ScriptedModelClient.text("I think it is fine."));
        StrategyOutcome outcome =
                llm(model, AgentBudget.standard(), PriceTable.empty())
                        .run(fixtures.task, fixtures.workspace);
        assertThat(outcome.status()).isEqualTo("PARTIAL");
        assertThat(outcome.hasPatch()).isFalse();
        assertThat(outcome.unresolved())
                .hasSize(2)
                .allMatch(u -> u.contains("did not contain the updated file"));
        assertThat(outcome.trace()).allMatch(step -> step.error());
    }

    @Test
    void aReplyMissingTheFinalNewlineKeepsTheFilesStyle() throws IOException {
        fixtures(renameOnly(), null);
        String service =
                current(SERVICE)
                        .replace("client.charge(5);", "client.createCharge(5);")
                        .stripTrailing();
        ScriptedModelClient model =
                ScriptedModelClient.replying(
                        ScriptedModelClient.text("UNCHANGED"),
                        ScriptedModelClient.text(
                                "<file path=\"" + SERVICE + "\">\n" + service + "</file>"));
        llm(model, AgentBudget.standard(), PriceTable.empty())
                .run(fixtures.task, fixtures.workspace);
        assertThat(current(SERVICE)).endsWith("}\n").doesNotContain("\n\n}\n");
    }

    @Test
    void aModelFailureStopsFurtherFiles() throws IOException {
        fixtures(renameOnly(), null);
        ScriptedModelClient model = ScriptedModelClient.replying();
        StrategyOutcome outcome =
                llm(model, AgentBudget.standard(), PriceTable.empty())
                        .run(fixtures.task, fixtures.workspace);
        assertThat(outcome.status()).isEqualTo("FAILED");
        assertThat(model.requests()).hasSize(1);
        assertThat(outcome.unresolved()).singleElement().asString().contains("no more replies");
    }

    @Test
    void theTokenAndCostBudgetsSkipRemainingFiles() throws IOException {
        fixtures(renameOnly(), null);
        ScriptedModelClient model =
                ScriptedModelClient.replying(
                        ScriptedModelClient.text("UNCHANGED"),
                        ScriptedModelClient.text("UNCHANGED"));
        StrategyOutcome outcome =
                llm(model, AgentBudget.standard().withMaxTokens(10), PriceTable.empty())
                        .run(fixtures.task, fixtures.workspace);
        assertThat(outcome.status()).isEqualTo("BUDGET");
        assertThat(model.requests()).hasSize(1);
        assertThat(outcome.unresolved()).singleElement().asString().contains("budget exhausted");
    }

    @Test
    void llmOnlyCostsFollowThePriceTable() throws IOException {
        fixtures(renameOnly(), null);
        PriceTable prices =
                PriceTable.of(
                        Map.of(
                                ScriptedModelClient.MODEL_ID,
                                new ModelPrice(1_000_000, 0, 1_000_000)));
        ScriptedModelClient model =
                ScriptedModelClient.replying(
                        ScriptedModelClient.text("UNCHANGED"),
                        ScriptedModelClient.text("UNCHANGED"));
        StrategyOutcome outcome =
                llm(model, AgentBudget.standard(), prices).run(fixtures.task, fixtures.workspace);
        assertThat(outcome.costMicros()).isEqualTo(40);
    }

    @Test
    void hybridNeverCallsTheModelWhenCodemodsCoverEverything() throws IOException {
        List<String> scopes = new ArrayList<>();
        SandboxPort sandbox =
                (root, scope) -> {
                    scopes.add(scope);
                    return TestRun.passing(4);
                };
        fixtures(renameOnly(), sandbox);
        ScriptedModelClient model = ScriptedModelClient.replying();
        StrategyOutcome outcome =
                new HybridStrategy(fixtures.loop(model, sandbox), sandbox)
                        .run(fixtures.task, fixtures.workspace);
        assertThat(model.requests()).isEmpty();
        assertThat(outcome.strategy()).isEqualTo("agent");
        assertThat(outcome.status()).isEqualTo("VERIFIED");
        assertThat(outcome.verified()).isTrue();
        assertThat(outcome.costMicros()).isZero();
        assertThat(scopes).containsExactly("all");
        assertThat(outcome.changedPaths()).containsExactly(BILLING, SERVICE);
    }

    @Test
    void hybridReportsFailingVerificationAfterAFullyAutomatedFix() throws IOException {
        SandboxPort sandbox = (root, scope) -> TestRun.failing(List.of("BillingTest.bill: boom"));
        fixtures(renameOnly(), sandbox);
        StrategyOutcome outcome =
                new HybridStrategy(fixtures.loop(ScriptedModelClient.replying(), sandbox), sandbox)
                        .run(fixtures.task, fixtures.workspace);
        assertThat(outcome.status()).isEqualTo("TESTS_FAILING");
        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.unresolved()).singleElement().asString().contains("verification fails");
    }

    @Test
    void hybridWithoutASandboxIsUnverified() throws IOException {
        fixtures(renameOnly(), null);
        StrategyOutcome outcome =
                new HybridStrategy(fixtures.loop(ScriptedModelClient.replying(), null), null)
                        .run(fixtures.task, fixtures.workspace);
        assertThat(outcome.status()).isEqualTo("UNVERIFIED");
        assertThat(outcome.unresolved()).contains("the tests were not run");
    }

    @Test
    void hybridHandsOnlyTheRemainingPlacesToTheAgent() throws IOException {
        SandboxPort sandbox = (root, scope) -> TestRun.passing(6);
        fixtures(renameAndRemoval(), sandbox);
        ScriptedModelClient model =
                ScriptedModelClient.replying(
                        call(
                                "apply_edit",
                                Map.of(
                                        "path", SERVICE,
                                        "search", "        client.legacy();\n",
                                        "replace", "")),
                        call(
                                "finish",
                                Map.of("summary", "removed the legacy call", "confidence", 0.8)));
        StrategyOutcome outcome =
                new HybridStrategy(fixtures.loop(model, sandbox), sandbox)
                        .run(fixtures.task, fixtures.workspace);
        String firstPrompt = model.requests().get(0).messages().get(0).text();
        assertThat(firstPrompt)
                .contains("Automated edits already fixed 2 places")
                .contains("found 1 affected places across 1 changes");
        assertThat(outcome.status()).isEqualTo("VERIFIED");
        assertThat(outcome.verified()).isTrue();
        assertThat(outcome.usage().totalTokens()).isEqualTo(40);
        assertThat(current(SERVICE)).contains("createCharge(5)").doesNotContain("legacy");
        assertThat(current(BILLING)).contains("createCharge(9)");
        assertThat(outcome.trace()).extracting(t -> t.kind()).contains("model", "tool", "tests");
    }

    @Test
    void hybridWithNothingAffectedReportsNoChanges() throws IOException {
        fixtures(new ChangeEventSet(List.of()), null);
        StrategyOutcome outcome =
                new HybridStrategy(fixtures.loop(ScriptedModelClient.replying(), null), null)
                        .run(fixtures.task, fixtures.workspace);
        assertThat(outcome.status()).isEqualTo("NO_CHANGES");
        assertThat(outcome.hasPatch()).isFalse();
    }
}
