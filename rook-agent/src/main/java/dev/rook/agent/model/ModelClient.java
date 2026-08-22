package dev.rook.agent.model;

public interface ModelClient {

    String modelId();

    ModelResponse complete(ModelRequest request);
}
