package dev.rook.agent.model.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.model.ModelException;
import dev.rook.agent.model.ModelMessage;
import dev.rook.agent.model.ModelRequest;
import dev.rook.agent.model.ModelResponse;
import dev.rook.agent.model.StopReason;
import dev.rook.agent.model.ToolCall;
import dev.rook.agent.model.ToolDefinition;
import dev.rook.agent.model.Usage;
import dev.rook.core.json.Json;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OpenAiCompatibleClientTest {

    private static final String KEY = "token-test-secret";

    private FakeApi api;
    private OpenAiCompatibleClient client;

    @BeforeEach
    void setUp() {
        api = new FakeApi();
        client =
                new OpenAiCompatibleClient(
                        api.base() + "/v1", new ApiKey(KEY), "model-y", Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    private static ModelRequest request() {
        ToolDefinition tool =
                new ToolDefinition("read_file", "reads", Json.tree(Map.of("type", "object")));
        return ModelRequest.of("You fix code.", List.of(ModelMessage.user("go")), 128)
                .withTools(List.of(tool));
    }

    @Test
    void plainReplyWithUsageAndCachedTokens() {
        api.reply(
                200,
                """
                {"model":"model-y-1","choices":[{"finish_reason":"stop",
                  "message":{"role":"assistant","content":"done"}}],
                 "usage":{"prompt_tokens":50,"completion_tokens":6,
                          "prompt_tokens_details":{"cached_tokens":20}}}
                """);
        ModelResponse response = client.complete(request());
        assertThat(response.text()).isEqualTo("done");
        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.usage()).isEqualTo(new Usage(50, 6, 20));
        assertThat(response.model()).isEqualTo("model-y-1");
        assertThat(client.modelId()).isEqualTo("model-y");
    }

    @Test
    void toolCallArgumentsAreParsedFromTheirJsonString() {
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"tool_calls","message":{"content":null,
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"read_file","arguments":"{\\"path\\":\\"a.txt\\"}"}}]}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """);
        ModelResponse response = client.complete(request());
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.toolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(response.toolCalls().get(0).arguments().path("path").asString())
                .isEqualTo("a.txt");
    }

    @Test
    void aToolCallWrittenAsJsonTextIsRecognisedWhenTheToolWasOffered() {
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "content":"{\\"name\\": \\"read_file\\", \\"arguments\\": {\\"path\\": \\"a.txt\\"}}"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """);
        ModelResponse response = client.complete(request());
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.text()).isEmpty();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(response.toolCalls().get(0).arguments().path("path").asString())
                .isEqualTo("a.txt");
    }

    @Test
    void providerDataOnAToolCallIsSentBackOnTheNextTurn() {
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"tool_calls","message":{"content":null,
                  "tool_calls":[{"id":"call_1","type":"function",
                    "extra_content":{"google":{"thought_signature":"sig-1"}},
                    "function":{"name":"read_file","arguments":"{\\"path\\":\\"a.txt\\"}"}}]}}],
                 "usage":{}}
                """);
        ModelResponse first = client.complete(request());
        assertThat(
                        first.toolCalls()
                                .get(0)
                                .extra()
                                .path("google")
                                .path("thought_signature")
                                .asString())
                .isEqualTo("sig-1");

        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"stop","message":{"content":"done"}}],"usage":{}}
                """);
        ModelRequest next =
                ModelRequest.of(
                                "You fix code.",
                                List.of(
                                        ModelMessage.user("go"),
                                        ModelMessage.assistant("", first.toolCalls()),
                                        ModelMessage.toolResult("call_1", "contents")),
                                128)
                        .withTools(request().tools());
        client.complete(next);

        JsonNode sent = Json.tree(api.received().get(1).body()).path("messages");
        JsonNode call = sent.path(2).path("tool_calls").path(0);
        assertThat(call.path("extra_content").path("google").path("thought_signature").asString())
                .isEqualTo("sig-1");
    }

    @Test
    void aToolCallWithoutProviderDataSendsNoExtraField() {
        ModelRequest plain =
                ModelRequest.of(
                                "You fix code.",
                                List.of(
                                        ModelMessage.user("go"),
                                        ModelMessage.assistant(
                                                "",
                                                List.of(
                                                        new ToolCall(
                                                                "c1",
                                                                "read_file",
                                                                Json.tree(Map.of("path", "a"))))),
                                        ModelMessage.toolResult("c1", "x")),
                                128)
                        .withTools(request().tools());
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"stop","message":{"content":"ok"}}],"usage":{}}
                """);
        client.complete(plain);

        JsonNode call =
                Json.tree(api.received().get(0).body())
                        .path("messages")
                        .path(2)
                        .path("tool_calls")
                        .path(0);
        assertThat(call.has("extra_content")).isFalse();
    }

    @Test
    void aFencedJsonToolCallIsRecognisedToo() {
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "content":"```json\\n{\\"name\\": \\"read_file\\", \\"arguments\\": {\\"path\\": \\"b.txt\\"}}\\n```"}}],
                 "usage":{}}
                """);
        ModelResponse response = client.complete(request());
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).arguments().path("path").asString())
                .isEqualTo("b.txt");
    }

    @Test
    void jsonTextNamingAToolThatWasNotOfferedStaysText() {
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "content":"{\\"name\\": \\"delete_everything\\", \\"arguments\\": {}}"}}],
                 "usage":{}}
                """);
        ModelResponse response = client.complete(request());
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.text()).contains("delete_everything");
    }

    @Test
    void ordinaryTextAndBrokenJsonAreNotToolCalls() {
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "content":"{not json at all}"}}],"usage":{}}
                """);
        assertThat(client.complete(request()).toolCalls()).isEmpty();
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "content":"I will read the file next."}}],"usage":{}}
                """);
        assertThat(client.complete(request()).toolCalls()).isEmpty();
    }

    @Test
    void brokenToolArgumentsAreRejected() {
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"tool_calls","message":{"content":"",
                  "tool_calls":[{"id":"c","type":"function",
                    "function":{"name":"read_file","arguments":"{not json"}}]}}],"usage":{}}
                """);
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("not valid json");
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"tool_calls","message":{"content":"",
                  "tool_calls":[{"id":"c","type":"function",
                    "function":{"name":"read_file","arguments":"[1]"}}]}}],"usage":{}}
                """);
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("not an object");
    }

    @Test
    void emptyArgumentsBecomeAnEmptyObject() {
        api.reply(
                200,
                """
                {"model":"m","choices":[{"finish_reason":"tool_calls","message":{"content":"",
                  "tool_calls":[{"id":"c","type":"function",
                    "function":{"name":"list_impacted","arguments":""}}]}}],"usage":{}}
                """);
        assertThat(client.complete(request()).toolCalls().get(0).arguments().isObject()).isTrue();
    }

    @Test
    void finishReasonsAreMapped() {
        for (String[] pair :
                new String[][] {{"length", "MAX_TOKENS"}, {"content_filter", "OTHER"}}) {
            api.reply(
                    200,
                    "{\"model\":\"m\",\"choices\":[{\"finish_reason\":\""
                            + pair[0]
                            + "\",\"message\":{\"content\":\"x\"}}],\"usage\":{}}");
            assertThat(client.complete(request()).stopReason().name()).isEqualTo(pair[1]);
        }
    }

    @Test
    void theRequestUsesBearerAuthSystemMessageAndFunctionTools() {
        api.reply(
                200,
                "{\"model\":\"m\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"x\"}}],\"usage\":{}}");
        client.complete(request());
        FakeApi.Received received = api.received().get(0);
        assertThat(received.path()).isEqualTo("/v1/chat/completions");
        assertThat(received.headers()).containsEntry("authorization", "Bearer " + KEY);
        JsonNode body = Json.tree(received.body());
        assertThat(body.path("messages").path(0).path("role").asString()).isEqualTo("system");
        assertThat(body.path("messages").path(1).path("content").asString()).isEqualTo("go");
        assertThat(body.path("tools").path(0).path("type").asString()).isEqualTo("function");
        assertThat(body.path("tools").path(0).path("function").path("name").asString())
                .isEqualTo("read_file");
        assertThat(received.body()).doesNotContain(KEY);
    }

    @Test
    void assistantToolCallsAndToolResultsRoundTrip() {
        api.reply(
                200,
                "{\"model\":\"m\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"x\"}}],\"usage\":{}}");
        ToolCall call = new ToolCall("c1", "read_file", Json.tree(Map.of("path", "a")));
        client.complete(
                ModelRequest.of(
                        "",
                        List.of(
                                ModelMessage.user("go"),
                                ModelMessage.assistant("reading", List.of(call)),
                                ModelMessage.toolResult("c1", "alpha")),
                        50));
        JsonNode messages = Json.tree(api.received().get(0).body()).path("messages");
        assertThat(messages).hasSize(3);
        JsonNode assistant = messages.path(1);
        assertThat(assistant.path("tool_calls").path(0).path("id").asString()).isEqualTo("c1");
        assertThat(
                        Json.tree(
                                        assistant
                                                .path("tool_calls")
                                                .path(0)
                                                .path("function")
                                                .path("arguments")
                                                .asString())
                                .path("path")
                                .asString())
                .isEqualTo("a");
        assertThat(messages.path(2).path("role").asString()).isEqualTo("tool");
        assertThat(messages.path(2).path("tool_call_id").asString()).isEqualTo("c1");
    }

    @Test
    void errorStatusesFollowTheRetryRules() {
        api.reply(503, "{\"error\":\"overloaded\"}");
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOfSatisfying(
                        ModelException.class, e -> assertThat(e.retryable()).isTrue());
        api.reply(400, "{\"error\":\"bad request\"}");
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOfSatisfying(
                        ModelException.class,
                        e -> {
                            assertThat(e.retryable()).isFalse();
                            assertThat(e.getMessage()).doesNotContain(KEY);
                        });
    }

    @Test
    void repliesWithoutAMessageAreRejected() {
        api.reply(200, "{\"model\":\"m\",\"choices\":[]}");
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("no message");
    }
}
