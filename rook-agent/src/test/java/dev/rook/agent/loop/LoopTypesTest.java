package dev.rook.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.model.Usage;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LoopTypesTest {

    @Test
    void aTestRunPassesOnlyWhenItCompiledRanTestsAndNothingFailed() {
        assertThat(TestRun.passing(3).passed()).isTrue();
        assertThat(TestRun.passing(0).passed()).isFalse();
        assertThat(TestRun.failing(List.of("a")).passed()).isFalse();
        assertThat(TestRun.notCompiling("error").passed()).isFalse();
        assertThat(new TestRun(true, 5, 0, List.of(), "", true).passed()).isFalse();
    }

    @Test
    void descriptionsSayWhatWentWrong() {
        assertThat(TestRun.passing(4).describe()).isEqualTo("4 tests ran and all passed");
        assertThat(TestRun.passing(0).describe()).contains("no tests were executed");
        assertThat(TestRun.notCompiling("Foo.java:3 missing symbol").describe())
                .contains("does not compile")
                .contains("missing symbol");
        assertThat(new TestRun(true, 5, 0, List.of(), "", true).describe()).contains("timed out");
        assertThat(TestRun.failing(List.of("A: x", "B: y")).describe())
                .contains("2 of 3 tests failed")
                .contains("- A: x")
                .contains("- B: y");
    }

    @Test
    void longFailureListsAreCut() {
        List<String> many = IntStream.range(0, 30).mapToObj(i -> "T" + i).toList();
        String text = TestRun.failing(many).describe();
        assertThat(text).contains("- T19").doesNotContain("- T20").contains("and 10 more");
    }

    @Test
    void budgetsRejectNonsense() {
        assertThatThrownBy(() -> AgentBudget.standard().withMaxIterations(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentBudget.standard().withWallClock(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentBudget.standard().withMaxRepairRounds(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentBudget.standard().withMaxTokens(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentBudget.standard().withToolCallLimits(0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentBudget.standard().withMaxCostMicros(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void budgetsKeepTheirOtherLimitsWhenOneChanges() {
        AgentBudget budget = AgentBudget.standard().withMaxIterations(5).withMaxCostMicros(10);
        assertThat(budget.maxIterations()).isEqualTo(5);
        assertThat(budget.maxCostMicros()).isEqualTo(10);
        assertThat(budget.maxRepairRounds()).isEqualTo(AgentBudget.standard().maxRepairRounds());
        assertThat(budget.wallClock()).isEqualTo(AgentBudget.standard().wallClock());
    }

    @Test
    void anAgentResultIsAcceptableOnlyWhenVerifiedWithNothingUnresolved() {
        AgentResult verified = result(AgentResult.Status.VERIFIED, List.of());
        AgentResult withLeftovers = result(AgentResult.Status.VERIFIED, List.of("one place"));
        AgentResult unverified = result(AgentResult.Status.UNVERIFIED, List.of());
        assertThat(verified.acceptable()).isTrue();
        assertThat(withLeftovers.acceptable()).isFalse();
        assertThat(unverified.acceptable()).isFalse();
        assertThat(verified.hasPatch()).isTrue();
    }

    private static AgentResult result(AgentResult.Status status, List<String> unresolved) {
        return new AgentResult(
                status,
                "s",
                0.5,
                unresolved,
                List.of(),
                "diff",
                List.of("a"),
                Usage.NONE,
                0,
                1,
                0,
                List.of());
    }
}
