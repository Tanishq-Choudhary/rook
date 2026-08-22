package dev.rook.agent.model.http;

import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.ModelException;
import dev.rook.agent.model.ModelMessage;
import dev.rook.agent.model.ModelRequest;
import dev.rook.agent.model.ModelResponse;
import dev.rook.agent.model.StopReason;
import dev.rook.agent.model.ToolCall;
import dev.rook.agent.model.ToolDefinition;
import dev.rook.agent.model.Usage;
import dev.rook.core.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class AnthropicClient implements ModelClient {

    public static final String DEFAULT_BASE = "https://api.anthropic.com";
    public static final String API_VERSION = "2023-06-01";

    private final URI endpoint;
    private final ApiKey apiKey;
    private final String model;
    private final JsonTransport transport;

    public AnthropicClient(String baseUri, ApiKey apiKey, String model, Duration timeout) {
        this(baseUri, apiKey, model, timeout, JsonTransport.defaultClient());
    }

    AnthropicClient(
            String baseUri, ApiKey apiKey, String model, Duration timeout, HttpClient client) {
        this.endpoint = URI.create(trimSlash(baseUri) + "/v1/messages");
        this.apiKey = apiKey;
        this.model = model;
        this.transport = new JsonTransport(client, timeout);
    }

    private static String trimSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        long started = System.nanoTime();
        JsonNode reply =
                transport.post(
                        endpoint,
                        Map.of("x-api-key", apiKey.value(), "anthropic-version", API_VERSION),
                        Json.write(body(request)));
        return parse(reply, Duration.ofNanos(System.nanoTime() - started));
    }

    ObjectNode body(ModelRequest request) {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        if (!request.system().isBlank()) {
            ArrayNode system = body.putArray("system");
            ObjectNode block = system.addObject();
            block.put("type", "text");
            block.put("text", request.system());
            block.putObject("cache_control").put("type", "ephemeral");
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode node = tools.addObject();
                node.put("name", tool.name());
                node.put("description", tool.description());
                node.set("input_schema", tool.inputSchema());
            }
        }
        body.set("messages", messages(request.messages()));
        return body;
    }

    private static ArrayNode messages(List<ModelMessage> messages) {
        ArrayNode out = Json.mapper().createArrayNode();
        ArrayNode pendingResults = null;
        for (ModelMessage message : messages) {
            if (message.role() == ModelMessage.Role.TOOL_RESULT) {
                if (pendingResults == null) {
                    ObjectNode user = out.addObject();
                    user.put("role", "user");
                    pendingResults = user.putArray("content");
                }
                ObjectNode result = pendingResults.addObject();
                result.put("type", "tool_result");
                result.put("tool_use_id", message.toolCallId());
                result.put("content", message.text());
                continue;
            }
            pendingResults = null;
            ObjectNode node = out.addObject();
            if (message.role() == ModelMessage.Role.USER) {
                node.put("role", "user");
                node.put("content", message.text());
                continue;
            }
            node.put("role", "assistant");
            ArrayNode content = node.putArray("content");
            if (!message.text().isBlank()) {
                content.addObject().put("type", "text").put("text", message.text());
            }
            for (ToolCall call : message.toolCalls()) {
                ObjectNode use = content.addObject();
                use.put("type", "tool_use");
                use.put("id", call.id());
                use.put("name", call.name());
                use.set("input", call.arguments());
            }
        }
        return out;
    }

    private ModelResponse parse(JsonNode reply, Duration latency) {
        JsonNode content = reply.path("content");
        if (!content.isArray()) {
            throw new ModelException("model api reply has no content", false);
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : content) {
            String type = block.path("type").asString("");
            if (type.equals("text")) {
                text.append(block.path("text").asString(""));
            } else if (type.equals("tool_use")) {
                calls.add(
                        new ToolCall(
                                block.path("id").asString(""),
                                block.path("name").asString(""),
                                block.path("input").isObject()
                                        ? block.path("input")
                                        : Json.mapper().createObjectNode()));
            }
        }
        JsonNode usage = reply.path("usage");
        long cached = usage.path("cache_read_input_tokens").asLong(0);
        long created = usage.path("cache_creation_input_tokens").asLong(0);
        long input = usage.path("input_tokens").asLong(0) + cached + created;
        return new ModelResponse(
                text.toString(),
                calls,
                new Usage(input, usage.path("output_tokens").asLong(0), cached),
                stop(reply.path("stop_reason").asString("")),
                reply.path("model").asString(model),
                latency);
    }

    private static StopReason stop(String reason) {
        return switch (reason) {
            case "end_turn", "stop_sequence" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }
}
