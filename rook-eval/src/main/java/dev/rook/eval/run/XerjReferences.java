package dev.rook.eval.run;

import dev.rook.agent.tools.ReferenceSearch;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.verify.sandbox.SandboxResult;
import dev.rook.verify.sandbox.SandboxRunner;
import dev.rook.verify.sandbox.SandboxSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class XerjReferences implements ReferenceSearch {

    private static final Map<String, String> ENV = Map.of("XERJ_DISABLE_FEEDBACK", "true");

    private final SandboxRunner runner;
    private final String binary;
    private final String url;
    private final String prefix;
    private final Path source;
    private final Duration timeout;

    private XerjReferences(
            SandboxRunner runner,
            String binary,
            String url,
            String prefix,
            Path source,
            Duration timeout) {
        this.runner = runner;
        this.binary = binary;
        this.url = url;
        this.prefix = prefix;
        this.source = source;
        this.timeout = timeout;
    }

    public static XerjReferences index(
            SandboxRunner runner,
            String binary,
            String url,
            String prefix,
            Path source,
            Duration timeout) {
        XerjReferences references =
                new XerjReferences(runner, binary, url, prefix, source, timeout);
        SandboxResult result =
                references.run(
                        List.of(
                                binary,
                                "autoindex",
                                source.toString(),
                                "--prefix",
                                prefix,
                                "--url",
                                url));
        if (!result.succeeded()) {
            throw new RookException(
                    ErrorCode.UNAVAILABLE,
                    "xerj could not index " + source + ": " + lastLine(result));
        }
        return references;
    }

    @Override
    public List<Passage> search(String query, int limit) {
        SandboxResult result =
                run(
                        List.of(
                                binary,
                                "search",
                                "--url",
                                url,
                                "--prefix",
                                prefix,
                                "-k",
                                Integer.toString(limit),
                                "--json",
                                query));
        if (!result.succeeded()) {
            throw new RookException(
                    ErrorCode.UNAVAILABLE, "xerj search failed: " + lastLine(result));
        }
        return passages(result.stdout());
    }

    static List<Passage> passages(String json) {
        List<Passage> passages = new ArrayList<>();
        for (JsonNode hit : Json.tree(json).path("hits").path("hits")) {
            JsonNode found = hit.path("_source");
            JsonNode passage = hit.path("fields").path("_passage").path(0);
            passages.add(
                    new Passage(
                            found.path("ax_path").asString(),
                            hit.path("match_line").asInt(found.path("start_line").asInt(1)),
                            passage.path("text").asString()));
        }
        return passages;
    }

    private SandboxResult run(List<String> command) {
        return runner.run(
                SandboxSpec.of("local", source.toString(), command)
                        .withTimeout(timeout)
                        .withEnv(ENV));
    }

    private static String lastLine(SandboxResult result) {
        String[] lines = result.combinedOutput().strip().split("\\R");
        return lines[lines.length - 1];
    }
}
