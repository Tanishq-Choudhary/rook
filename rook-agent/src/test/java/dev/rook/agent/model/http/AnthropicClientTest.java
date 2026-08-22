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

class AnthropicClientTest {

    private static final String KEY = "sk-test-secret-value";

    private FakeApi api;
    private AnthropicClient client;

    @BeforeEach
    void setUp() {
        api = new FakeApi();
        client = new AnthropicClient(api.base(), new ApiKey(KEY), "model-x", Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    private static ModelRequest request() {
        ToolDefinition tool =
                new ToolDefinition(
                        "read_file",
                        "reads a file",
                        Json.tree(Map.of("type", "object", "properties", Map.of())));
        return new ModelRequest(
                        "You fix code.", List.of(ModelMessage.user("go")), List.of(), 256, 0)
                .withTools(List.of(tool));
    }

    private static final String TEXT_REPLY =
            """
            {"id":"m1","model":"model-x-2","stop_reason":"end_turn",
             "content":[{"type":"text","text":"hello "},{"type":"text","text":"world"}],
             "usage":{"input_tokens":10,"output_tokens":4}}
            """;

    @Test
    void textRepliesAreConcatenatedWithUsageAndModel() {
        api.reply(200, TEXT_REPLY);
        ModelResponse response = client.complete(request());
        assertThat(response.text()).isEqualTo("hello world");
        assertThat(response.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(response.usage()).isEqualTo(new Usage(10, 4, 0));
        assertThat(response.model()).isEqualTo("model-x-2");
        assertThat(response.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(client.modelId()).isEqualTo("model-x");
    }

    @Test
    void toolUseBlocksBecomeToolCalls() {
        api.reply(
                200,
                """
                {"model":"m","stop_reason":"tool_use",
                 "content":[{"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"a.txt"}}],
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """);
        ModelResponse response = client.complete(request());
        assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("toolu_1");
        assertThat(call.arguments().path("path").asString()).isEqualTo("a.txt");
    }

    @Test
    void cacheTokensCountTowardsInputAndAreReportedAsCached() {
        api.reply(
                200,
                """
                {"model":"m","stop_reason":"end_turn","content":[{"type":"text","text":"x"}],
                 "usage":{"input_tokens":5,"cache_read_input_tokens":100,
                          "cache_creation_input_tokens":20,"output_tokens":7}}
                """);
        assertThat(client.complete(request()).usage()).isEqualTo(new Usage(125, 7, 100));
    }

    @Test
    void stopReasonsAreMapped() {
        for (String[] pair :
                new String[][] {
                    {"max_tokens", "MAX_TOKENS"},
                    {"stop_sequence", "END_TURN"},
                    {"refusal", "OTHER"}
                }) {
            api.reply(
                    200,
                    "{\"model\":\"m\",\"stop_reason\":\""
                            + pair[0]
                            + "\",\"content\":[],\"usage\":{}}");
            assertThat(client.complete(request()).stopReason().name()).isEqualTo(pair[1]);
        }
    }

    @Test
    void theRequestCarriesTheKeyHeaderCachedSystemPromptAndTools() {
        api.reply(200, TEXT_REPLY);
        client.complete(request());
        FakeApi.Received received = api.received().get(0);
        assertThat(received.path()).isEqualTo("/v1/messages");
        assertThat(received.headers())
                .containsEntry("x-api-key", KEY)
                .containsEntry("anthropic-version", AnthropicClient.API_VERSION);
        JsonNode body = Json.tree(received.body());
        assertThat(body.path("model").asString()).isEqualTo("model-x");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(body.path("system").path(0).path("text").asString()).isEqualTo("You fix code.");
        assertThat(body.path("system").path(0).path("cache_control").path("type").asString())
                .isEqualTo("ephemeral");
        assertThat(body.path("tools").path(0).path("name").asString()).isEqualTo("read_file");
        assertThat(body.path("tools").path(0).path("input_schema").path("type").asString())
                .isEqualTo("object");
        assertThat(body.path("messages").path(0).path("content").asString()).isEqualTo("go");
        assertThat(received.body()).doesNotContain(KEY);
    }

    @Test
    void requestsWithoutSystemOrToolsOmitThoseFields() {
        api.reply(200, TEXT_REPLY);
        client.complete(ModelRequest.of(null, List.of(ModelMessage.user("go")), 10));
        JsonNode body = Json.tree(api.received().get(0).body());
        assertThat(body.has("system")).isFalse();
        assertThat(body.has("tools")).isFalse();
    }

    @Test
    void consecutiveToolResultsShareOneUserMessage() {
        api.reply(200, TEXT_REPLY);
        ToolCall one = new ToolCall("c1", "read_file", Json.tree(Map.of("path", "a")));
        ToolCall two = new ToolCall("c2", "read_file", Json.tree(Map.of("path", "b")));
        client.complete(
                ModelRequest.of(
                        "s",
                        List.of(
                                ModelMessage.user("go"),
                                ModelMessage.assistant("reading", List.of(one, two)),
                                ModelMessage.toolResult("c1", "alpha"),
                                ModelMessage.toolResult("c2", "beta"),
                                ModelMessage.user("continue")),
                        50));
        JsonNode messages = Json.tree(api.received().get(0).body()).path("messages");
        assertThat(messages).hasSize(4);
        JsonNode assistant = messages.path(1);
        assertThat(assistant.path("content").path(0).path("type").asString()).isEqualTo("text");
        assertThat(assistant.path("content").path(2).path("id").asString()).isEqualTo("c2");
        JsonNode results = messages.path(2);
        assertThat(results.path("role").asString()).isEqualTo("user");
        assertThat(results.path("content")).hasSize(2);
        assertThat(results.path("content").path(1).path("tool_use_id").asString()).isEqualTo("c2");
        assertThat(results.path("content").path(1).path("content").asString()).isEqualTo("beta");
        assertThat(messages.path(3).path("content").asString()).isEqualTo("continue");
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryable() {
        for (int status : new int[] {429, 500, 529, 408}) {
            api.reply(status, "{\"error\":\"busy\"}");
            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOfSatisfying(
                            ModelException.class, e -> assertThat(e.retryable()).isTrue());
        }
    }

    @Test
    void clientErrorsAreNotRetryableAndNeverEchoTheKey() {
        api.reply(401, "{\"error\":{\"message\":\"invalid x-api-key\"}}");
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOfSatisfying(
                        ModelException.class,
                        e -> {
                            assertThat(e.retryable()).isFalse();
                            assertThat(e.getMessage()).contains("401").doesNotContain(KEY);
                        });
    }

    @Test
    void unreadableOrContentFreeRepliesAreRejected() {
        api.reply(200, "this is not json");
        assertThatThrownBy(() -> client.complete(request())).isInstanceOf(ModelException.class);
        api.reply(200, "{\"model\":\"m\"}");
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("no content");
    }

    @Test
    void aSlowServerTimesOutAsRetryable() {
        AnthropicClient impatient =
                new AnthropicClient(api.base(), new ApiKey(KEY), "model-x", Duration.ofMillis(200));
        api.replySlowly(TEXT_REPLY, 1500);
        assertThatThrownBy(() -> impatient.complete(request()))
                .isInstanceOfSatisfying(
                        ModelException.class,
                        e -> {
                            assertThat(e.retryable()).isTrue();
                            assertThat(e.getMessage()).contains("timed out");
                        });
    }

    @Test
    void anUnreachableServerIsRetryable() {
        api.close();
        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOfSatisfying(
                        ModelException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void trailingSlashesInTheBaseAreTolerated() {
        AnthropicClient slashed =
                new AnthropicClient(
                        api.base() + "/", new ApiKey(KEY), "model-x", Duration.ofSeconds(5));
        api.reply(200, TEXT_REPLY);
        slashed.complete(request());
        assertThat(api.received().get(0).path()).isEqualTo("/v1/messages");
    }
}
