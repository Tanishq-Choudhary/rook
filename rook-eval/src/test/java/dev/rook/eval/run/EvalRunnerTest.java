package dev.rook.eval.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.model.Usage;
import dev.rook.agent.strategy.CodemodOnlyStrategy;
import dev.rook.agent.strategy.Strategy;
import dev.rook.agent.strategy.StrategyOutcome;
import dev.rook.agent.workspace.Workspace;
import dev.rook.agent.workspace.WorkspacePolicy;
import dev.rook.codeintel.impact.Confidence;
import dev.rook.eval.fixture.Fixture;
import dev.rook.eval.generate.FixtureGenerator;
import dev.rook.eval.verify.FixtureVerifier;
import dev.rook.eval.verify.SdkVersion;
import dev.rook.eval.verify.Verdict;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalRunnerTest {

    @TempDir static Path shared;

    static List<Fixture> fixtures;
    static EvalRunner runner;
    static final Map<String, FixtureResult> CODEMOD = new ConcurrentHashMap<>();

    static final FixtureVerifier GOLD_COMPARISON =
            new FixtureVerifier() {
                @Override
                public String name() {
                    return "gold-comparison";
                }

                @Override
                public Verdict verify(Fixture fixture, Path repoRoot, SdkVersion sdk) {
                    Map<String, String> expected = Fixture.readTree(fixture.repo());
                    expected.putAll(fixture.goldFiles());
                    boolean same = Fixture.readTree(repoRoot).equals(expected);
                    return new Verdict(
                            true,
                            1,
                            same ? List.of() : List.of("app.ServiceTest#differs"),
                            1,
                            List.of(),
                            0,
                            "");
                }
            };

    @BeforeAll
    static void runCodemodsOverEverything() {
        fixtures = FixtureGenerator.generate(shared.resolve("fixtures"));
        runner =
                new EvalRunner(
                        GOLD_COMPARISON,
                        shared.resolve("work"),
                        Confidence.HEURISTIC,
                        WorkspacePolicy.standard());
        fixtures.parallelStream()
                .forEach(f -> CODEMOD.put(f.id(), runner.run(f, new CodemodOnlyStrategy())));
    }

    private static List<FixtureResult> of(String category) {
        return CODEMOD.values().stream().filter(r -> r.category().equals(category)).toList();
    }

    @Test
    void deterministicCodemodsReproduceTheGoldPatchForMechanicalChanges() {
        for (String category :
                List.of(
                        "method-rename",
                        "class-move",
                        "argument-removed",
                        "name-collision",
                        "string-mention")) {
            assertThat(of(category)).hasSize(8);
            assertThat(of(category))
                    .as(category)
                    .allSatisfy(
                            result -> {
                                assertThat(result.accepted()).as(result.fixtureId()).isTrue();
                                assertThat(result.patchProduced()).isTrue();
                                assertThat(result.falseEdits()).isEmpty();
                                assertThat(result.regressionEscape()).isFalse();
                            });
        }
    }

    @Test
    void changesThatNeedJudgementAreLeftForTheModel() {
        for (String category : List.of("parameter-type-changed", "return-type-changed")) {
            assertThat(of(category))
                    .as(category)
                    .allSatisfy(
                            result -> {
                                assertThat(result.patchProduced()).as(result.fixtureId()).isFalse();
                                assertThat(result.accepted()).isFalse();
                                assertThat(result.failure()).isEqualTo("no patch produced");
                            });
        }
    }

    @Test
    void harmlessUpdatesProduceNoPatchAndStayAccepted() {
        for (String category : List.of("deprecation-only", "unrelated-update")) {
            assertThat(of(category))
                    .as(category)
                    .allSatisfy(
                            result -> {
                                assertThat(result.patchProduced()).as(result.fixtureId()).isFalse();
                                assertThat(result.accepted()).isTrue();
                                assertThat(result.falseEdits()).isEmpty();
                            });
        }
    }

    @Test
    void impactCountsComeFromTheAnalysisOfTheSameRun() {
        assertThat(CODEMOD.values())
                .allSatisfy(
                        result -> {
                            assertThat(result.impactFalsePositives()).isZero();
                            assertThat(result.impactFalseNegatives()).isZero();
                            assertThat(result.decision()).isNotBlank();
                        });
        assertThat(of("unrelated-update"))
                .allSatisfy(result -> assertThat(result.decision()).isEqualTo("NO_IMPACT"));
    }

    @Test
    void editingAProtectedFileIsAFalseEditEvenWhenTheTestsPass() {
        Fixture trap =
                fixtures.stream()
                        .filter(f -> f.id().equals("name-collision-pay-field"))
                        .findFirst()
                        .orElseThrow();
        Strategy careless =
                new Strategy() {
                    @Override
                    public String name() {
                        return "careless";
                    }

                    @Override
                    public StrategyOutcome run(AgentTask task, Workspace workspace) {
                        for (var entry : trap.goldFiles().entrySet()) {
                            workspace.rewrite(entry.getKey(), entry.getValue());
                        }
                        String ledger = "src/main/java/app/Ledger.java";
                        workspace.rewrite(ledger, workspace.read(ledger) + "\n");
                        return new StrategyOutcome(
                                name(),
                                workspace.diff(),
                                workspace.changedPaths(),
                                List.of(),
                                Usage.NONE,
                                120,
                                15,
                                false,
                                "DONE",
                                List.of());
                    }
                };
        FixtureResult result = runner.run(trap, careless);
        assertThat(result.falseEdits()).containsExactly("src/main/java/app/Ledger.java");
        assertThat(result.visiblePassed()).isFalse();
        assertThat(result.accepted()).isFalse();
        assertThat(result.costMicros()).isEqualTo(120);
        assertThat(result.failure()).isNotBlank();
    }

    @Test
    void anyEditOnANoopFixtureCountsAsFalse() {
        Fixture quiet = fixtures.stream().filter(f -> f.spec().noop()).findFirst().orElseThrow();
        assertThat(EvalRunner.falseEdits(quiet, List.of("src/main/java/app/Service.java")))
                .containsExactly("src/main/java/app/Service.java");
        assertThat(EvalRunner.falseEdits(quiet, List.of())).isEmpty();
    }

    @Test
    void aStrategyThatThrowsIsReportedAsAnErrorRow() {
        Fixture any = fixtures.get(0);
        Strategy broken =
                new Strategy() {
                    @Override
                    public String name() {
                        return "broken";
                    }

                    @Override
                    public StrategyOutcome run(AgentTask task, Workspace workspace) {
                        throw new IllegalStateException("model unavailable");
                    }
                };
        FixtureResult result = runner.run(any, broken);
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.failure()).contains("model unavailable");
        assertThat(result.accepted()).isFalse();
        assertThat(result.patchProduced()).isFalse();
    }

    @Test
    void theNewSdkApiCanBeLeftOutOfTheTask() {
        Fixture typeChange =
                fixtures.stream()
                        .filter(f -> f.id().equals("parameter-type-changed-blob-field"))
                        .findFirst()
                        .orElseThrow();
        Map<String, String> prefaces = new ConcurrentHashMap<>();
        runner.run(typeChange, recording("with-notes", prefaces), true);
        runner.run(typeChange, recording("without-notes", prefaces), false);
        assertThat(prefaces.get("with-notes")).contains("Ref");
        assertThat(prefaces.get("without-notes")).isEmpty();
    }

    private static Strategy recording(String name, Map<String, String> prefaces) {
        return new Strategy() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public StrategyOutcome run(AgentTask task, Workspace workspace) {
                prefaces.put(name, task.preface());
                return new CodemodOnlyStrategy().run(task, workspace);
            }
        };
    }
}
