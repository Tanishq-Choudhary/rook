package dev.rook.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptedModelClientTest {

    private static ModelRequest request(String text) {
        return ModelRequest.of("system", List.of(ModelMessage.user(text)), 100);
    }

    @Test
    void repliesAreReturnedInOrderAndRequestsAreRecorded() {
        ScriptedModelClient client =
                ScriptedModelClient.replying(
                        ScriptedModelClient.toolCall("read_file", Map.of("path", "a.txt")),
                        ScriptedModelClient.text("done"));
        ModelResponse first = client.complete(request("one"));
        ModelResponse second = client.complete(request("two"));
        assertThat(first.wantsTools()).isTrue();
        assertThat(first.toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(first.toolCalls().get(0).arguments().path("path").asString()).isEqualTo("a.txt");
        assertThat(first.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(second.text()).isEqualTo("done");
        assertThat(client.requests()).hasSize(2);
        assertThat(client.requests().get(1).messages().get(0).text()).isEqualTo("two");
        assertThat(client.remaining()).isZero();
        assertThat(client.modelId()).isEqualTo(ScriptedModelClient.MODEL_ID);
    }

    @Test
    void anExhaustedScriptFailsWithoutRetrying() {
        ScriptedModelClient client = ScriptedModelClient.replying(ScriptedModelClient.text("x"));
        client.complete(request("one"));
        assertThatThrownBy(() -> client.complete(request("two")))
                .isInstanceOfSatisfying(
                        ModelException.class, e -> assertThat(e.retryable()).isFalse());
    }

    @Test
    void stepsCanDependOnTheRequest() {
        ScriptedModelClient client =
                ScriptedModelClient.of(
                        r -> ScriptedModelClient.text("echo " + r.messages().get(0).text()));
        assertThat(client.complete(request("ping")).text()).isEqualTo("echo ping");
    }

    @Test
    void generatedToolCallIdsAreUnique() {
        ModelResponse a = ScriptedModelClient.toolCall("t", Map.of());
        ModelResponse b = ScriptedModelClient.toolCall("t", Map.of());
        assertThat(a.toolCalls().get(0).id()).isNotEqualTo(b.toolCalls().get(0).id());
    }
}
