package dev.rook.codeintel.project;

public record Dependency(
        String groupId, String artifactId, String version, String scope, String declaredIn) {

    public String coordinates() {
        return groupId + ":" + artifactId;
    }
}
