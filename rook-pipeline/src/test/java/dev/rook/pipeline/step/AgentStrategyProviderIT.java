package dev.rook.pipeline.step;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.loop.AgentBudget;
import dev.rook.agent.loop.SandboxPort;
import dev.rook.agent.loop.TestRun;
import dev.rook.agent.model.ModelPrice;
import dev.rook.agent.model.PriceTable;
import dev.rook.agent.model.ScriptedModelClient;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.control.budget.BudgetService;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.pipeline.PipelineHarness;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.workflow.JobRunner;
import dev.rook.workflow.RunResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentStrategyProviderIT {

    @TempDir Path temp;

    private PipelineHarness harness;
    private JobRunner runner;
    private ScriptedModelClient model;
    private final List<String> testScopes = new ArrayList<>();
    private TestRun testResult = TestRun.passing(4);

    @BeforeEach
    void setUp() {
        harness = new PipelineHarness(temp);
        harness.seedShop();
    }

    @AfterEach
    void tearDown() {
        runner.close();
        harness.close();
    }

    private void start(ScriptedModelClient scripted, AgentBudget budget) {
        model = scripted;
        SandboxPort sandbox =
                (root, scope) -> {
                    testScopes.add(scope);
                    return testResult;
                };
        AgentStrategyProvider provider =
                new AgentStrategyProvider(
                        model,
                        PriceTable.of(
                                Map.of(
                                        ScriptedModelClient.MODEL_ID,
                                        new ModelPrice(1_000_000, 0, 2_000_000))),
                        budget,
                        jobId -> sandbox,
                        harness.clock);
        runner =
                harness.runner(
                        List.of(
                                new IndexingHandler(
                                        harness.ingestion::load,
                                        harness.access,
                                        harness.artifacts,
                                        harness.audit,
                                        harness.workRoot,
                                        RepoAnalyzer.Options.defaults().withoutSymbolResolution()),
                                new ProposingHandler(
                                        harness.ingestion::load,
                                        harness.access,
                                        harness.repositories,
                                        harness.policies,
                                        harness.artifacts,
                                        harness.budgets,
                                        harness.audit,
                                        provider,
                                        harness.workRoot)));
    }

    private static ChangeEvent returnTypeChange() {
        return ChangeEvent.of(
                "acme-sdk",
                "1.0.0",
                "2.0.0",
                ChangeKind.METHOD_RETURN_TYPE_CHANGED,
                SymbolRef.javaMethod("com.acme.pay.PaymentClient", "charge", List.of("long")),
                Map.of(Attr.NAME, "charge"),
                "charge now returns a Receipt");
    }

    private static ScriptedModelClient editAndFinish() {
        return ScriptedModelClient.replying(
                ScriptedModelClient.toolCall("list_impacted", Map.of()),
                ScriptedModelClient.toolCall(
                        "apply_edit",
                        Map.of(
                                "path",
                                PipelineHarness.SERVICE,
                                "search",
                                "return client.charge(5);",
                                "replace",
                                "return client.charge(5).id();")),
                ScriptedModelClient.toolCall(
                        "finish",
                        Map.of("summary", "read the id from the receipt", "confidence", 0.9)));
    }

    @Test
    void theModelFixesWhatTheCodemodsCannotAndTheCostIsSettled() {
        start(editAndFinish(), AgentBudget.standard());
        UUID job = harness.job(List.of(returnTypeChange()));
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(RunResult.Outcome.ADVANCED);
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.ADVANCED);
        assertThat(harness.state(job)).isEqualTo("VERIFYING");
        ArtifactStore.StoredProposal proposal = harness.artifacts.latestProposal(job).orElseThrow();
        assertThat(proposal.strategy()).isEqualTo("agent");
        assertThat(proposal.status()).isEqualTo("VERIFIED");
        assertThat(proposal.files().get(PipelineHarness.SERVICE)).contains("client.charge(5).id()");
        assertThat(proposal.costMicros()).isPositive();
        assertThat(proposal.trace().size()).isPositive();
        assertThat(testScopes).isNotEmpty();
        assertThat(harness.budgets.jobSpend(job)).isEqualTo(proposal.costMicros());
        BudgetService.Usage usage = harness.budgets.usage(harness.installation.id());
        assertThat(usage.reservedMicros()).isZero();
        assertThat(usage.spentMicros()).isEqualTo(proposal.costMicros());
        assertThat(model.requests()).isNotEmpty();
    }

    @Test
    void theAgentBudgetIsReservedBeforeTheModelIsCalled() {
        harness.budgets.setBudget(harness.installation.id(), new BudgetService.Caps(10, 10));
        start(editAndFinish(), AgentBudget.standard());
        UUID job = harness.job(List.of(returnTypeChange()));
        runner.runOnce("w1");
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.FINISHED);
        assertThat(harness.state(job)).isEqualTo("REJECTED");
        assertThat(model.requests()).isEmpty();
    }

    @Test
    void failingTestsInTheSandboxKeepTheAgentFromClaimingSuccess() {
        testResult = TestRun.failing(List.of("ServiceTest.go: expected 1"));
        start(editAndFinish(), AgentBudget.standard().withMaxIterations(4));
        UUID job = harness.job(List.of(returnTypeChange()));
        runner.runOnce("w1");
        runner.runOnce("w1");
        ArtifactStore.StoredProposal proposal = harness.artifacts.latestProposal(job).orElseThrow();
        assertThat(proposal.status()).isNotEqualTo("VERIFIED");
    }

    @Test
    void aModelThatGivesNoAnswerFailsTheJobAndFreesTheReservation() {
        start(ScriptedModelClient.replying(), AgentBudget.standard());
        UUID job = harness.job(List.of(returnTypeChange()));
        runner.runOnce("w1");
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome())
                .isIn(RunResult.Outcome.RETRY_SCHEDULED, RunResult.Outcome.FINISHED);
        assertThat(harness.budgets.usage(harness.installation.id()).reservedMicros()).isZero();
        assertThat(harness.budgets.jobSpend(job)).isZero();
    }
}
