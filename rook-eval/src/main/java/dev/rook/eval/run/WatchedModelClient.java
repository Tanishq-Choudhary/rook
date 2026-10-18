package dev.rook.eval.run;

import dev.rook.agent.model.ModelClient;
import dev.rook.agent.model.ModelException;
import dev.rook.agent.model.ModelRequest;
import dev.rook.agent.model.ModelResponse;

public final class WatchedModelClient implements ModelClient {

    private final ModelClient delegate;
    private String failure;
    private long calls;
    private long input;
    private long output;

    public WatchedModelClient(ModelClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        try {
            ModelResponse response = delegate.complete(request);
            synchronized (this) {
                calls++;
                input += response.usage().inputTokens();
                output += response.usage().outputTokens();
            }
            return response;
        } catch (ModelException e) {
            synchronized (this) {
                failure = e.getMessage();
            }
            throw e;
        }
    }

    public synchronized long[] takeUsage() {
        long[] taken = {calls, input, output};
        calls = 0;
        input = 0;
        output = 0;
        return taken;
    }

    public synchronized String takeFailure() {
        String taken = failure;
        failure = null;
        return taken;
    }
}
