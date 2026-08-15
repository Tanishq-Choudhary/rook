package dev.rook.codeintel.graph;

import dev.rook.codeintel.graph.ImpactGraph.Edge;
import dev.rook.codeintel.graph.ImpactGraph.Node;
import dev.rook.codeintel.impact.Impact;
import dev.rook.codeintel.impact.ImpactLocation;
import dev.rook.codeintel.impact.ImpactReport;
import dev.rook.codeintel.java.CodeIndex;
import dev.rook.codeintel.java.Model.FileIndex;
import dev.rook.codeintel.java.Model.Usage;
import dev.rook.codeintel.java.Model.UsageKind;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ImpactGraphBuilder {

    public static final int MAX_NODES = 300;
    public static final int DEFAULT_CALLER_DEPTH = 2;

    private final CodeIndex index;
    private final int callerDepth;

    public ImpactGraphBuilder(CodeIndex index, int callerDepth) {
        this.index = index;
        this.callerDepth = callerDepth;
    }

    public ImpactGraph build(ImpactReport report) {
        if (!report.hasImpact()) {
            return ImpactGraph.empty();
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        Set<Edge> edges = new LinkedHashSet<>();
        boolean[] truncated = {false};

        for (Impact impact : report.impacts()) {
            String changeId = "change:" + impact.fingerprint().substring(0, 12);
            add(
                    nodes,
                    truncated,
                    new Node(
                            changeId,
                            "CHANGE",
                            impact.kind().name() + " " + impact.subject(),
                            null,
                            null,
                            impact.summary()));
            Set<String> symbols = new LinkedHashSet<>();
            for (ImpactLocation location : impact.locations()) {
                String symbol = symbolOf(location);
                String fileId = "file:" + location.file();
                add(
                        nodes,
                        truncated,
                        new Node(
                                fileId,
                                "FILE",
                                location.file(),
                                location.file(),
                                null,
                                location.test() ? "test" : "main"));
                if (symbol == null) {
                    edges.add(
                            new Edge(
                                    changeId,
                                    fileId,
                                    "touches:" + location.confidence().name().toLowerCase()));
                    continue;
                }
                String symbolId = "symbol:" + symbol;
                add(
                        nodes,
                        truncated,
                        new Node(
                                symbolId,
                                "SYMBOL",
                                symbol,
                                location.file(),
                                location.line(),
                                location.referenceKind()));
                edges.add(
                        new Edge(
                                changeId,
                                symbolId,
                                "touches:" + location.confidence().name().toLowerCase()));
                edges.add(new Edge(symbolId, fileId, "declared_in"));
                symbols.add(symbol);
            }
            for (String symbol : symbols) {
                propagate(symbol, nodes, edges, truncated);
            }
        }
        List<Node> orderedNodes = new ArrayList<>(nodes.values());
        orderedNodes.sort(Comparator.comparing(Node::kind).thenComparing(Node::id));
        List<Edge> orderedEdges = new ArrayList<>(edges);
        orderedEdges.sort(
                Comparator.comparing(Edge::from).thenComparing(Edge::to).thenComparing(Edge::kind));
        return new ImpactGraph(orderedNodes, orderedEdges, truncated[0]);
    }

    private void propagate(
            String symbol, Map<String, Node> nodes, Set<Edge> edges, boolean[] truncated) {
        Deque<String[]> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(new String[] {symbol, "0"});
        seen.add(symbol);
        while (!queue.isEmpty()) {
            String[] current = queue.poll();
            int depth = Integer.parseInt(current[1]);
            if (depth >= callerDepth) {
                continue;
            }
            for (Caller caller : callersOf(current[0])) {
                if (!seen.add(caller.symbol())) {
                    continue;
                }
                String callerId = "symbol:" + caller.symbol();
                add(
                        nodes,
                        truncated,
                        new Node(
                                callerId,
                                "SYMBOL",
                                caller.symbol(),
                                caller.file(),
                                caller.line(),
                                "caller"));
                String fileId = "file:" + caller.file();
                add(
                        nodes,
                        truncated,
                        new Node(fileId, "FILE", caller.file(), caller.file(), null, "main"));
                edges.add(new Edge("symbol:" + current[0], callerId, "called_by"));
                edges.add(new Edge(callerId, fileId, "declared_in"));
                queue.add(new String[] {caller.symbol(), Integer.toString(depth + 1)});
            }
        }
    }

    private List<Caller> callersOf(String symbol) {
        int hash = symbol.indexOf('#');
        if (hash < 0) {
            return List.of();
        }
        String type = symbol.substring(0, hash);
        String method = symbol.substring(hash + 1);
        if (method.startsWith("<")) {
            return List.of();
        }
        String simple = type.substring(type.lastIndexOf('.') + 1);
        List<Caller> callers = new ArrayList<>();
        for (FileIndex file : index.files()) {
            for (Usage usage : file.usages()) {
                if (usage.kind() != UsageKind.METHOD_CALL
                        || !usage.name().equals(method)
                        || usage.enclosing().isEmpty()) {
                    continue;
                }
                boolean sameType =
                        usage.scope() == null && usage.enclosing().startsWith(type + "#");
                boolean typed = usage.scopeType() != null && usage.scopeType().endsWith(simple);
                if ((sameType || typed) && !usage.enclosing().equals(symbol)) {
                    callers.add(
                            new Caller(usage.enclosing(), file.path(), usage.location().line()));
                }
            }
        }
        callers.sort(Comparator.comparing(Caller::symbol));
        return callers;
    }

    private static String symbolOf(ImpactLocation location) {
        return location.enclosing() == null || location.enclosing().isBlank()
                ? null
                : location.enclosing();
    }

    private static void add(Map<String, Node> nodes, boolean[] truncated, Node node) {
        if (nodes.containsKey(node.id())) {
            return;
        }
        if (nodes.size() >= MAX_NODES) {
            truncated[0] = true;
            return;
        }
        nodes.put(node.id(), node);
    }

    private record Caller(String symbol, String file, int line) {}
}
