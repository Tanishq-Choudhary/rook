package dev.rook.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.model.ToolCall;
import dev.rook.agent.model.ToolDefinition;
import dev.rook.agent.patch.PatchRejected;
import dev.rook.agent.workspace.WorkspaceViolation;
import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ToolRegistryTest {

    interface Body {
        ToolResult run(JsonNode arguments) throws Exception;
    }

    private static Tool tool(String name, Body body) {
        ToolDefinition definition =
                new ToolDefinition(
                        name,
                        "test tool",
                        Schemas.object(
                                Map.of("value", Schemas.string("v", 1, 50)), List.of("value")));
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(JsonNode arguments, ToolContext context) throws Exception {
                return body.run(arguments);
            }
        };
    }

    private static ToolCall call(String name, Object arguments) {
        return new ToolCall("c1", name, Json.tree(arguments));
    }

    private final List<String> seen = new ArrayList<>();
    private ToolRegistry registry =
            new ToolRegistry(
                    new ToolRegistry.Limits(Duration.ofMillis(300), 200),
                    (call, result) -> seen.add(call.name() + ":" + result.error()));

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void aRegisteredToolRunsAndTheListenerSeesIt() {
        registry.register(
                tool("echo", args -> ToolResult.ok("got " + args.path("value").asString())));
        ToolResult result = registry.execute(call("echo", Map.of("value", "x")), null);
        assertThat(result.text()).isEqualTo("got x");
        assertThat(result.error()).isFalse();
        assertThat(result.millis()).isGreaterThanOrEqualTo(0);
        assertThat(seen).containsExactly("echo:false");
        assertThat(registry.has("echo")).isTrue();
        assertThat(registry.definitions()).extracting(ToolDefinition::name).containsExactly("echo");
    }

    @Test
    void unknownToolsAreReportedToTheModel() {
        ToolResult result = registry.execute(call("nope", Map.of()), null);
        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("unknown tool nope");
    }

    @Test
    void argumentsAreValidatedBeforeTheToolRuns() {
        List<String> ran = new ArrayList<>();
        registry.register(
                tool(
                        "strict",
                        args -> {
                            ran.add("ran");
                            return ToolResult.ok("x");
                        }));
        ToolResult missing = registry.execute(call("strict", Map.of()), null);
        ToolResult extra = registry.execute(call("strict", Map.of("value", "a", "other", 1)), null);
        assertThat(missing.error()).isTrue();
        assertThat(missing.text()).contains("invalid arguments").contains("$.value is required");
        assertThat(extra.text()).contains("not an allowed property");
        assertThat(ran).isEmpty();
    }

    @Test
    void aSlowToolTimesOutAndIsReportedAsAnError() {
        registry.register(
                tool(
                        "slow",
                        args -> {
                            Thread.sleep(5_000);
                            return ToolResult.ok("late");
                        }));
        ToolResult result = registry.execute(call("slow", Map.of("value", "x")), null);
        assertThat(result.error()).isTrue();
        assertThat(result.text()).contains("timed out");
    }

    @Test
    void longOutputIsCutWithAMarker() {
        registry.register(tool("big", args -> ToolResult.ok("x".repeat(1000))));
        ToolResult result = registry.execute(call("big", Map.of("value", "x")), null);
        assertThat(result.text())
                .startsWith("x".repeat(200))
                .contains("[output cut, 800 more characters]");
    }

    @Test
    void knownFailuresBecomeReadableMessages() {
        registry.register(
                tool(
                        "rejected",
                        args -> {
                            throw new PatchRejected(PatchRejected.Reason.AMBIGUOUS, "twice");
                        }));
        registry.register(
                tool(
                        "violating",
                        args -> {
                            throw new WorkspaceViolation(WorkspaceViolation.Code.DENIED, "no");
                        }));
        registry.register(
                tool(
                        "invalid",
                        args -> {
                            throw RookException.invalid("bad input");
                        }));
        assertThat(registry.execute(call("rejected", Map.of("value", "x")), null).text())
                .isEqualTo("edit rejected (AMBIGUOUS): twice");
        assertThat(registry.execute(call("violating", Map.of("value", "x")), null).text())
                .isEqualTo("DENIED: no");
        assertThat(registry.execute(call("invalid", Map.of("value", "x")), null).text())
                .contains("bad input");
    }

    @Test
    void unexpectedFailuresDoNotLeakTheirDetails() {
        registry.register(
                tool(
                        "crash",
                        args -> {
                            throw new IllegalStateException("password=hunter2 in stack");
                        }));
        ToolResult result = registry.execute(call("crash", Map.of("value", "x")), null);
        assertThat(result.error()).isTrue();
        assertThat(result.text()).isEqualTo("crash failed unexpectedly");
    }

    @Test
    void aFailingListenerDoesNotBreakTheCall() {
        ToolRegistry loud =
                new ToolRegistry(
                        ToolRegistry.Limits.standard(),
                        (call, result) -> {
                            throw new IllegalStateException("listener down");
                        });
        loud.register(tool("echo", args -> ToolResult.ok("fine")));
        assertThat(loud.execute(call("echo", Map.of("value", "x")), null).text()).isEqualTo("fine");
        loud.close();
    }

    @Test
    void duplicateAndUnsupportedRegistrationsAreRefused() {
        registry.register(tool("once", args -> ToolResult.ok("x")));
        assertThatThrownBy(() -> registry.register(tool("once", args -> ToolResult.ok("y"))))
                .isInstanceOf(IllegalArgumentException.class);
        ToolDefinition odd =
                new ToolDefinition(
                        "odd", "d", Json.tree(Map.of("type", "string", "format", "uri")));
        assertThatThrownBy(
                        () ->
                                registry.register(
                                        new Tool() {
                                            @Override
                                            public ToolDefinition definition() {
                                                return odd;
                                            }

                                            @Override
                                            public ToolResult execute(
                                                    JsonNode arguments, ToolContext context) {
                                                return ToolResult.ok("x");
                                            }
                                        }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsMustBeSensible() {
        assertThatThrownBy(() -> new ToolRegistry.Limits(Duration.ZERO, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolRegistry.Limits(Duration.ofSeconds(1), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
