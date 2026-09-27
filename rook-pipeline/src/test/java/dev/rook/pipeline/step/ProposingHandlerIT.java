package dev.rook.pipeline.step;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.model.Usage;
import dev.rook.agent.strategy.CodemodOnlyStrategy;
import dev.rook.agent.strategy.Strategy;
import dev.rook.agent.strategy.StrategyOutcome;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.RepoAnalyzer;
import dev.rook.control.budget.BudgetService;
import dev.rook.control.policy.Policy;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.SymbolRef;
import dev.rook.pipeline.PipelineHarness;
import dev.rook.pipeline.store.ArtifactStore;
import dev.rook.workflow.JobRunner;
import dev.rook.workflow.RunResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProposingHandlerIT {

    @TempDir Path temp;

    private PipelineHarness harness;
    private final AtomicReference<StrategyProvider> provider =
            new AtomicReference<>(
                    jobId -> StrategyProvider.Prepared.free(new CodemodOnlyStrategy()));
    private JobRunner runner;

    @BeforeEach
    void setUp() {
        harness = new PipelineHarness(temp);
        harness.seedShop();
        IndexingHandler indexing =
                new IndexingHandler(
                        harness.ingestion::load,
                        harness.access,
                        harness.artifacts,
                        harness.audit,
                        harness.workRoot,
                        RepoAnalyzer.Options.defaults().withoutSymbolResolution());
        ProposingHandler proposing =
                new ProposingHandler(
                        harness.ingestion::load,
                        harness.access,
                        harness.repositories,
                        harness.policies,
                        harness.artifacts,
                        harness.budgets,
                        harness.audit,
                        jobId -> provider.get().prepare(jobId),
                        harness.workRoot);
        runner = harness.runner(List.of(indexing, proposing));
    }

    @AfterEach
    void tearDown() {
        runner.close();
        harness.close();
    }

    private UUID jobAtProposing(List<ChangeEvent> events) {
        UUID job = harness.job(events);
        assertThat(runner.runOnce("w1").outcome()).isEqualTo(RunResult.Outcome.ADVANCED);
        assertThat(harness.state(job)).isEqualTo("PROPOSING");
        return job;
    }

    private static Strategy fixed(long cost, Usage usage, String diff, List<String> changed) {
        return new Strategy() {
            @Override
            public String name() {
                return "scripted";
            }

            @Override
            public StrategyOutcome run(AgentTask task, Workspace workspace) {
                return new StrategyOutcome(
                        "scripted",
                        diff,
                        changed,
                        List.of(),
                        usage,
                        cost,
                        5,
                        false,
                        "DONE",
                        List.of());
            }
        };
    }

    @Test
    void theCodemodPatchIsStoredAndTheJobMovesToVerifying() {
        UUID job = jobAtProposing(List.of(PipelineHarness.renameEvent()));
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.ADVANCED);
        assertThat(harness.state(job)).isEqualTo("VERIFYING");
        ArtifactStore.StoredProposal proposal = harness.artifacts.latestProposal(job).orElseThrow();
        assertThat(proposal.strategy()).isEqualTo("codemod-only");
        assertThat(proposal.files()).containsOnlyKeys(PipelineHarness.SERVICE);
        assertThat(proposal.files().get(PipelineHarness.SERVICE))
                .contains("client.createCharge(5)")
                .doesNotContain("client.charge(5)");
        assertThat(proposal.diff()).contains("-        return client.charge(5);");
        assertThat(proposal.costMicros()).isZero();
        assertThat(harness.audit.forSubject("job:" + job, 10))
                .extracting(entry -> entry.action())
                .containsExactly("IMPACT_ANALYZED", "PATCH_PROPOSED");
        assertThat(harness.workRoot.resolve("workspace-" + job)).doesNotExist();
        assertThat(harness.workRoot.resolve("propose-" + job)).doesNotExist();
    }

    @Test
    void theModelSpendIsReservedThenSettledAtTheActualCost() {
        provider.set(
                jobId ->
                        new StrategyProvider.Prepared(
                                fixed(
                                        120,
                                        new Usage(1000, 200, 0),
                                        "diff --git a/x b/x",
                                        List.of(PipelineHarness.SERVICE)),
                                500,
                                () -> {}));
        UUID job = jobAtProposing(List.of(PipelineHarness.renameEvent()));
        runner.runOnce("w1");
        assertThat(harness.budgets.jobSpend(job)).isEqualTo(120);
        BudgetService.Usage usage = harness.budgets.usage(harness.installation.id());
        assertThat(usage.spentMicros()).isEqualTo(120);
        assertThat(usage.reservedMicros()).isZero();
        assertThat(
                        harness.jdbc
                                .sql("select units from usage_ledger where job_id = :job")
                                .param("job", job)
                                .query(Long.class)
                                .single())
                .isEqualTo(1200);
    }

    @Test
    void anExhaustedBudgetStopsTheJobBeforeAnyWorkIsDone() {
        harness.budgets.setBudget(harness.installation.id(), new BudgetService.Caps(100, 100));
        AtomicInteger runs = new AtomicInteger();
        provider.set(
                jobId ->
                        new StrategyProvider.Prepared(
                                new Strategy() {
                                    @Override
                                    public String name() {
                                        return "counting";
                                    }

                                    @Override
                                    public StrategyOutcome run(
                                            AgentTask task, Workspace workspace) {
                                        runs.incrementAndGet();
                                        throw new IllegalStateException("should not run");
                                    }
                                },
                                500,
                                () -> {}));
        UUID job = jobAtProposing(List.of(PipelineHarness.renameEvent()));
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.FINISHED);
        assertThat(harness.state(job)).isEqualTo("REJECTED");
        assertThat(runs).hasValue(0);
        assertThat(harness.artifacts.latestProposal(job)).isEmpty();
        assertThat(harness.audit.forSubject("job:" + job, 10))
                .extracting(entry -> entry.action())
                .contains("BUDGET_DENIED");
    }

    @Test
    void aStrategyThatCannotProduceAPatchFailsTheJob() {
        ChangeEvent returnType =
                ChangeEvent.of(
                        "acme-sdk",
                        "1.0.0",
                        "2.0.0",
                        ChangeKind.METHOD_RETURN_TYPE_CHANGED,
                        SymbolRef.javaMethod(
                                "com.acme.pay.PaymentClient", "charge", List.of("long")),
                        Map.of(Attr.NAME, "charge"),
                        "charge now returns a Receipt");
        UUID job = jobAtProposing(List.of(returnType));
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.FINISHED);
        assertThat(harness.state(job)).isEqualTo("FAILED");
        assertThat(harness.jobs.find(job).orElseThrow().lastError())
                .contains("no patch could be produced");
        ArtifactStore.StoredProposal proposal = harness.artifacts.latestProposal(job).orElseThrow();
        assertThat(proposal.diff()).isEmpty();
        assertThat(proposal.unresolved()).isNotEmpty();
        assertThat(harness.audit.forSubject("job:" + job, 10))
                .extracting(entry -> entry.action())
                .contains("NO_PATCH");
    }

    @Test
    void aCrashingStrategyGivesTheReservationBackAndRetries() {
        provider.set(
                jobId ->
                        new StrategyProvider.Prepared(
                                new Strategy() {
                                    @Override
                                    public String name() {
                                        return "crashing";
                                    }

                                    @Override
                                    public StrategyOutcome run(
                                            AgentTask task, Workspace workspace) {
                                        throw new IllegalStateException("model unavailable");
                                    }
                                },
                                800,
                                () -> {}));
        UUID job = jobAtProposing(List.of(PipelineHarness.renameEvent()));
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.RETRY_SCHEDULED);
        assertThat(harness.state(job)).isEqualTo("PROPOSING");
        assertThat(harness.budgets.usage(harness.installation.id()).reservedMicros()).isZero();
        assertThat(harness.budgets.jobSpend(job)).isZero();
    }

    @Test
    void theRepositoryPolicyLimitsWhatTheWorkspaceMayChange() {
        harness.policies.save(
                harness.installation.id(),
                harness.repositoryId,
                Policy.standard().withLimits(1, 1));
        UUID job = jobAtProposing(List.of(PipelineHarness.renameEvent()));
        RunResult result = runner.runOnce("w1");
        assertThat(result.outcome()).isEqualTo(RunResult.Outcome.FINISHED);
        assertThat(harness.state(job)).isEqualTo("FAILED");
        assertThat(harness.artifacts.latestProposal(job).orElseThrow().diff()).isEmpty();
    }

    @Test
    void theStrategyProviderIsClosedAfterTheStep() {
        AtomicInteger closed = new AtomicInteger();
        provider.set(
                jobId ->
                        new StrategyProvider.Prepared(
                                new CodemodOnlyStrategy(), 0, closed::incrementAndGet));
        jobAtProposing(List.of(PipelineHarness.renameEvent()));
        runner.runOnce("w1");
        assertThat(closed).hasValue(1);
    }
}
