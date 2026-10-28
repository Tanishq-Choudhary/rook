package dev.rook.eval.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.agent.tools.ReferenceSearch.Passage;
import dev.rook.core.error.RookException;
import dev.rook.verify.sandbox.SandboxResult;
import dev.rook.verify.sandbox.SandboxRunner;
import dev.rook.verify.sandbox.SandboxSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XerjReferencesTest {

    private static final String SEARCH_REPLY =
            """
            {
              "hits": {
                "total": {"value": 2, "relation": "eq"},
                "hits": [
                  {
                    "_index": "geo-docs",
                    "_score": 0.23,
                    "_source": {"title": "Receipt.java", "language": "java", "start_line": 1,
                                "ax_path": "geokit/Receipt.java", "ax_locator": "code"},
                    "fields": {"_passage": [{"field": "title", "ordinal": 0, "text": "Receipt.java"}]},
                    "match_line": null
                  },
                  {
                    "_index": "geo-docs",
                    "_score": 0.22,
                    "_source": {"title": "GeoClient.java", "language": "java", "start_line": 1,
                                "ax_path": "geokit/GeoClient.java", "ax_locator": "code"},
                    "fields": {"_passage": [{"field": "body", "ordinal": 4,
                                "text": "public Receipt lookup(String key) {"}]},
                    "match_line": 5
                  }
                ]
              }
            }
            """;

    private final List<SandboxSpec> seen = new ArrayList<>();
    private final Deque<SandboxResult> replies = new ArrayDeque<>();

    private final SandboxRunner runner =
            new SandboxRunner() {
                @Override
                public String kind() {
                    return "stub";
                }

                @Override
                public SandboxResult run(SandboxSpec spec) {
                    seen.add(spec);
                    return replies.removeFirst();
                }
            };

    private static SandboxResult exit(int code, String stdout, String stderr) {
        return new SandboxResult(code, false, false, false, stdout, stderr, Duration.ofMillis(3));
    }

    private XerjReferences indexed(Path sdk) {
        return XerjReferences.index(
                runner, "xerj", "http://127.0.0.1:9200", "geo-fixture", sdk, Duration.ofMinutes(1));
    }

    @Test
    void indexingRunsAutoindexWithThePrefixAndFeedbackOff(@TempDir Path sdk) {
        replies.add(exit(0, "ok: indexed and searchable", ""));
        indexed(sdk);
        SandboxSpec spec = seen.get(0);
        assertThat(spec.command())
                .containsExactly(
                        "xerj",
                        "autoindex",
                        sdk.toString(),
                        "--prefix",
                        "geo-fixture",
                        "--url",
                        "http://127.0.0.1:9200");
        assertThat(spec.workdir()).isEqualTo(sdk.toString());
        assertThat(spec.env()).containsEntry("XERJ_DISABLE_FEEDBACK", "true");
    }

    @Test
    void aFailedIndexStopsBeforeAnySearch(@TempDir Path sdk) {
        replies.add(exit(2, "", "walking the folder\nconnection refused: 127.0.0.1:9200"));
        assertThatThrownBy(() -> indexed(sdk))
                .isInstanceOf(RookException.class)
                .hasMessageEndingWith("connection refused: 127.0.0.1:9200");
        assertThat(seen).hasSize(1);
    }

    @Test
    void searchAsksForJsonAndKeepsEveryHitInRankOrder(@TempDir Path sdk) {
        replies.add(exit(0, "ok", ""));
        replies.add(exit(0, SEARCH_REPLY, ""));
        List<Passage> passages = indexed(sdk).search("Receipt", 3);
        assertThat(seen.get(1).command())
                .containsExactly(
                        "xerj",
                        "search",
                        "--url",
                        "http://127.0.0.1:9200",
                        "--prefix",
                        "geo-fixture",
                        "-k",
                        "3",
                        "--json",
                        "Receipt");
        assertThat(passages)
                .containsExactly(
                        new Passage("geokit/Receipt.java", 1, "Receipt.java"),
                        new Passage(
                                "geokit/GeoClient.java", 5, "public Receipt lookup(String key) {"));
    }

    @Test
    void noHitsGiveAnEmptyList() {
        assertThat(XerjReferences.passages("{\"hits\":{\"hits\":[]}}")).isEmpty();
    }

    @Test
    void aFailedSearchIsAnError(@TempDir Path sdk) {
        replies.add(exit(0, "ok", ""));
        replies.add(exit(1, "", "no node at http://127.0.0.1:9200"));
        XerjReferences references = indexed(sdk);
        assertThatThrownBy(() -> references.search("Receipt", 5))
                .isInstanceOf(RookException.class)
                .hasMessage("xerj search failed: no node at http://127.0.0.1:9200");
    }
}
