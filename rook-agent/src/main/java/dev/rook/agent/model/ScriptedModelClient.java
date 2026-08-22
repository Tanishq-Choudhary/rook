package dev.rook.agent.model;

import dev.rook.core.id.Ids;
import dev.rook.core.json.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ScriptedModelClient implements ModelClient {

    public static final String MODEL_ID = "scripted";

    private final List<Function<ModelRequest, ModelResponse>> steps;
    private final List<ModelRequest> requests = new ArrayList<>();
    private int next;

    private ScriptedModelClient(List<Function<ModelRequest, ModelResponse>> steps) {
        this.steps = steps;
    }

    @SafeVarargs
    public static ScriptedModelClient of(Function<ModelRequest, ModelResponse>... steps) {
        return new ScriptedModelClient(List.of(steps));
    }

    public static ScriptedModelClient replying(ModelResponse... responses) {
        List<Function<ModelRequest, ModelResponse>> steps = new ArrayList<>();
        Arrays.stream(responses).forEach(response -> steps.add(request -> response));
        return new ScriptedModelClient(steps);
    }

    public static ModelResponse text(String text) {
        return new ModelResponse(
                text,
                List.of(),
                new Usage(10, 10, 0),
                StopReason.END_TURN,
                MODEL_ID,
                Duration.ZERO);
    }

    public static ModelResponse toolCall(String name, Map<String, ?> arguments) {
        ToolCall call = new ToolCall(Ids.newId().toString(), name, Json.tree(arguments));
        return new ModelResponse(
                "",
                List.of(call),
                new Usage(10, 10, 0),
                StopReason.TOOL_USE,
                MODEL_ID,
                Duration.ZERO);
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public synchronized ModelResponse complete(ModelRequest request) {
        requests.add(request);
        if (next >= steps.size()) {
            throw new ModelException("the script has no more replies", false);
        }
        return steps.get(next++).apply(request);
    }

    public synchronized List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    public synchronized int remaining() {
        return steps.size() - next;
    }
}
