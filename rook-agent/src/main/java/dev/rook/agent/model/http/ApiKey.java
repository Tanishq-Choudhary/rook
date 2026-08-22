package dev.rook.agent.model.http;

public record ApiKey(String value) {

    public ApiKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("an api key is required");
        }
    }

    @Override
    public String toString() {
        return "ApiKey[redacted]";
    }
}
