package dev.rook.codeintel.graph;

import java.util.List;

public record ImpactGraph(List<Node> nodes, List<Edge> edges, boolean truncated) {

    public record Node(
            String id, String kind, String label, String file, Integer line, String detail) {}

    public record Edge(String from, String to, String kind) {}

    public ImpactGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public static ImpactGraph empty() {
        return new ImpactGraph(List.of(), List.of(), false);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}
