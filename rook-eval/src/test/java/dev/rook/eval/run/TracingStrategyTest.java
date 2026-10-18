package dev.rook.eval.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rook.agent.loop.AgentTask;
import dev.rook.agent.loop.TraceStep;
import dev.rook.agent.model.Usage;
import dev.rook.agent.strategy.Strategy;
import dev.rook.agent.strategy.StrategyOutcome;
import dev.rook.agent.workspace.Workspace;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.core.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class TracingStrategyTest {

    private static AgentTask task(String fixture) {
        return new AgentTask(fixture, List.of(), ImpactReport.of(List.of()), List.of(), null, "");
    }

    private static Strategy returning(List<TraceStep> trace) {
        return new Strategy() {
            @Override
            public String name() {
                return "agent";
            }

            @Override
            public StrategyOutcome run(AgentTask task, Workspace workspace) {
                return new StrategyOutcome(
                        "agent",
                        "",
                        List.of("src/A.java"),
                        List.of("left over"),
                        Usage.NONE,
                        0,
                        5,
                        false,
                        "ITERATION_LIMIT",
                        trace);
            }
        };
    }

    @Test
    void theTraceIsWrittenUnderTheStrategyAndFixtureName(@TempDir Path temp) throws IOException {
        List<TraceStep> trace =
                List.of(
                        new TraceStep(1, "model", "m", "10 in, 2 out", 3, false),
                        new TraceStep(2, "tool", "apply_edit", "{} => no match", 4, true));
        TracingStrategy strategy = new TracingStrategy(returning(trace), temp.resolve("traces"));

        StrategyOutcome outcome = strategy.run(task("return-type-changed-geo-local"), null);

        assertThat(strategy.name()).isEqualTo("agent");
        assertThat(outcome.status()).isEqualTo("ITERATION_LIMIT");
        Path file = temp.resolve("traces/agent/return-type-changed-geo-local.json");
        assertThat(file).exists();
        JsonNode written = Json.tree(Files.readString(file));
        assertThat(written.path("fixture").asString()).isEqualTo("return-type-changed-geo-local");
        assertThat(written.path("status").asString()).isEqualTo("ITERATION_LIMIT");
        assertThat(written.path("trace")).hasSize(2);
        assertThat(written.path("trace").path(1).path("detail").asString()).contains("no match");
        assertThat(written.path("changedPaths").path(0).asString()).isEqualTo("src/A.java");
    }

    @Test
    void anEmptyTraceWritesNothing(@TempDir Path temp) {
        TracingStrategy strategy =
                new TracingStrategy(returning(List.of()), temp.resolve("traces"));

        strategy.run(task("a"), null);

        assertThat(temp.resolve("traces")).doesNotExist();
    }
}
