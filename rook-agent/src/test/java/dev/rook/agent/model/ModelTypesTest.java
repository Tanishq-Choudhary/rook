package dev.rook.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.json.Json;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelTypesTest {

    private static ToolCall call(String id) {
        return new ToolCall(id, "read_file", Json.tree(Map.of("path", "a.txt")));
    }

    @Test
    void toolResultsMustNameTheCallTheyAnswer() {
        assertThatThrownBy(() -> new ModelMessage(ModelMessage.Role.TOOL_RESULT, "x", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ModelMessage.toolResult("c1", "ok").toolCallId()).isEqualTo("c1");
    }

    @Test
    void onlyAssistantMessagesCarryToolCalls() {
        assertThatThrownBy(
                        () ->
                                new ModelMessage(
                                        ModelMessage.Role.USER, "x", List.of(call("c1")), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ModelMessage.assistant("x", List.of(call("c1"))).toolCalls()).hasSize(1);
    }

    @Test
    void nullTextBecomesEmpty() {
        assertThat(ModelMessage.user(null).text()).isEmpty();
    }

    @Test
    void toolCallsNeedAnIdAndAName() {
        assertThatThrownBy(() -> new ToolCall(" ", "x", Json.tree(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCall("1", "", Json.tree(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolNamesAreRestrictedToSafeCharacters() {
        assertThatThrownBy(() -> new ToolDefinition("bad name", "d", Json.tree(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ToolDefinition("read_file", "d", Json.tree(Map.of())).name())
                .isEqualTo("read_file");
    }

    @Test
    void usageAddsUpAndRejectsInconsistentCounts() {
        Usage total = new Usage(100, 20, 40).plus(new Usage(50, 10, 0));
        assertThat(total).isEqualTo(new Usage(150, 30, 40));
        assertThat(total.totalTokens()).isEqualTo(180);
        assertThatThrownBy(() -> new Usage(10, 0, 11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestsAreValidated() {
        assertThatThrownBy(() -> ModelRequest.of("s", List.of(), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelRequest.of("s", List.of(ModelMessage.user("x")), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new ModelRequest("s", List.of(ModelMessage.user("x")), null, 10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        ModelRequest request = ModelRequest.of(null, List.of(ModelMessage.user("x")), 10);
        assertThat(request.system()).isEmpty();
        assertThat(request.tools()).isEmpty();
        ToolDefinition tool = new ToolDefinition("t", "d", Json.tree(Map.of()));
        assertThat(request.withTools(List.of(tool)).tools()).containsExactly(tool);
    }

    @Test
    void responsesReportToolUseAndConvertToMessages() {
        ModelResponse response =
                new ModelResponse(
                        "thinking",
                        List.of(call("c1")),
                        null,
                        StopReason.TOOL_USE,
                        "m",
                        Duration.ofMillis(5));
        assertThat(response.wantsTools()).isTrue();
        assertThat(response.usage()).isEqualTo(Usage.NONE);
        assertThat(response.asMessage().role()).isEqualTo(ModelMessage.Role.ASSISTANT);
        assertThat(response.asMessage().toolCalls()).hasSize(1);
        assertThat(ScriptedModelClient.text("done").wantsTools()).isFalse();
    }

    @Test
    void modelExceptionsKnowWhetherToRetry() {
        assertThat(new ModelException("busy", true).retryable()).isTrue();
        assertThat(new ModelException("bad", false, new RuntimeException()).retryable()).isFalse();
    }
}
