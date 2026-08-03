package dev.rook.changes.openapi;

public record ApiParameter(
        String name, String location, boolean required, boolean deprecated, ApiSchema schema) {

    public String key() {
        return location + ":" + name;
    }
}
