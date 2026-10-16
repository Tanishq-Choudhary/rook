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

public final class OpenAiCompatibleClient implements ModelClient {

    private final URI endpoint;
    private final ApiKey apiKey;
    private final String model;
    private final JsonTransport transport;

    public OpenAiCompatibleClient(String baseUri, ApiKey apiKey, String model, Duration timeout) {
        this(baseUri, apiKey, model, timeout, JsonTransport.defaultClient());
    }

    OpenAiCompatibleClient(
            String baseUri, ApiKey apiKey, String model, Duration timeout, HttpClient client) {
        String base = baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
        this.endpoint = URI.create(base + "/chat/completions");
        this.apiKey = apiKey;
        this.model = model;
        this.transport = new JsonTransport(client, timeout);
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
                        Map.of("authorization", "Bearer " + apiKey.value()),
                        Json.write(body(request)));
        return parse(reply, Duration.ofNanos(System.nanoTime() - started), request);
    }

    ObjectNode body(ModelRequest request) {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        ArrayNode messages = body.putArray("messages");
        if (!request.system().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.system());
        }
        for (ModelMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            switch (message.role()) {
                case USER -> {
                    node.put("role", "user");
                    node.put("content", message.text());
                }
                case TOOL_RESULT -> {
                    node.put("role", "tool");
                    node.put("tool_call_id", message.toolCallId());
                    node.put("content", message.text());
                }
                case ASSISTANT -> {
                    node.put("role", "assistant");
                    node.put("content", message.text());
                    if (!message.toolCalls().isEmpty()) {
                        ArrayNode calls = node.putArray("tool_calls");
                        for (ToolCall call : message.toolCalls()) {
                            ObjectNode entry = calls.addObject();
                            entry.put("id", call.id());
                            entry.put("type", "function");
                            ObjectNode function = entry.putObject("function");
                            function.put("name", call.name());
                            function.put("arguments", Json.write(call.arguments()));
                            if (call.extra() != null) {
                                entry.set("extra_content", call.extra());
                            }
                        }
                    }
                }
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition tool : request.tools()) {
                ObjectNode entry = tools.addObject();
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.inputSchema());
            }
        }
        return body;
    }

    private ModelResponse parse(JsonNode reply, Duration latency, ModelRequest request) {
        JsonNode choice = reply.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw new ModelException("model api reply has no message", false);
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            calls.add(
                    new ToolCall(
                            call.path("id").asString(""),
                            call.path("function").path("name").asString(""),
                            arguments(call.path("function").path("arguments").asString("{}")),
                            call.path("extra_content").isObject()
                                    ? call.path("extra_content")
                                    : null));
        }
        String text = message.path("content").asString("");
        if (calls.isEmpty()) {
            ToolCall written = writtenCall(text, request);
            if (written != null) {
                calls.add(written);
                text = "";
            }
        }
        JsonNode usage = reply.path("usage");
        long input = usage.path("prompt_tokens").asLong(0);
        long cached =
                Math.min(
                        input, usage.path("prompt_tokens_details").path("cached_tokens").asLong(0));
        StopReason reason = stop(choice.path("finish_reason").asString(""));
        if (!calls.isEmpty() && reason == StopReason.END_TURN) {
            reason = StopReason.TOOL_USE;
        }
        return new ModelResponse(
                text,
                calls,
                new Usage(input, usage.path("completion_tokens").asLong(0), cached),
                reason,
                reply.path("model").asString(model),
                latency);
    }

    private static ToolCall writtenCall(String text, ModelRequest request) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int open = trimmed.indexOf('\n');
            int close = trimmed.lastIndexOf("```");
            if (open < 0 || close <= open) {
                return null;
            }
            trimmed = trimmed.substring(open + 1, close).strip();
        }
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        JsonNode node;
        try {
            node = Json.tree(trimmed);
        } catch (RuntimeException e) {
            return null;
        }
        String name = node.path("name").asString("");
        JsonNode arguments = node.path("arguments");
        boolean offered = request.tools().stream().anyMatch(tool -> tool.name().equals(name));
        if (!offered || !arguments.isObject()) {
            return null;
        }
        return new ToolCall("written-" + Math.abs(trimmed.hashCode()), name, arguments);
    }

    private static JsonNode arguments(String raw) {
        try {
            JsonNode node = Json.tree(raw.isBlank() ? "{}" : raw);
            if (!node.isObject()) {
                throw new ModelException("tool arguments are not an object", false);
            }
            return node;
        } catch (ModelException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelException("tool arguments are not valid json", false, e);
        }
    }

    private static StopReason stop(String reason) {
        return switch (reason) {
            case "stop" -> StopReason.END_TURN;
            case "tool_calls" -> StopReason.TOOL_USE;
            case "length" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }
}
