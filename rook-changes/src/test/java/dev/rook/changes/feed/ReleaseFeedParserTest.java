package dev.rook.changes.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.core.error.RookException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseFeedParserTest {

    @Test
    void parsesGitHubReleasesAndSkipsDrafts() {
        String json =
                """
                [
                  {"tag_name":"v2.0.0","name":"Acme 2.0","body":"notes","published_at":"2026-07-01T10:00:00Z","html_url":"https://example.test/r/2","draft":false,"prerelease":false},
                  {"tag_name":"v2.1.0-rc1","name":"rc","body":"","published_at":"2026-07-10T10:00:00Z","draft":false,"prerelease":true},
                  {"tag_name":"v3.0.0","name":"draft","body":"","draft":true}
                ]
                """;
        List<ReleaseEntry> entries = ReleaseFeedParser.parseGitHubReleases(json);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).version()).isEqualTo("2.0.0");
        assertThat(entries.get(0).publishedAt()).isEqualTo(Instant.parse("2026-07-01T10:00:00Z"));
        assertThat(entries.get(1).prerelease()).isTrue();
    }

    @Test
    void rejectsNonArrayGitHubPayloads() {
        assertThatThrownBy(
                        () ->
                                ReleaseFeedParser.parseGitHubReleases(
                                        "{\"message\":\"rate limited\"}"))
                .isInstanceOf(RookException.class);
    }

    @Test
    void parsesAtomFeeds() {
        String atom =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Releases</title>
                  <entry>
                    <title>v1.5.0</title>
                    <updated>2026-06-01T08:00:00Z</updated>
                    <link rel="alternate" href="https://example.test/releases/v1.5.0"/>
                    <content type="html">Removed the old client</content>
                  </entry>
                </feed>
                """;
        List<ReleaseEntry> entries = ReleaseFeedParser.parseAtomOrRss(atom);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).version()).isEqualTo("1.5.0");
        assertThat(entries.get(0).body()).contains("Removed the old client");
        assertThat(entries.get(0).url()).endsWith("v1.5.0");
    }

    @Test
    void parsesRssFeeds() {
        String rss =
                """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                  <item>
                    <title>Release 3.2.1</title>
                    <link>https://example.test/3.2.1</link>
                    <pubDate>Mon, 03 Aug 2026 10:00:00 GMT</pubDate>
                    <description>Bug fixes</description>
                  </item>
                </channel></rss>
                """;
        List<ReleaseEntry> entries = ReleaseFeedParser.parseAtomOrRss(rss);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).version()).isEqualTo("3.2.1");
        assertThat(entries.get(0).publishedAt()).isEqualTo(Instant.parse("2026-08-03T10:00:00Z"));
    }

    @Test
    void refusesDocumentsWithDoctypeDeclarations() {
        String hostile =
                """
                <?xml version="1.0"?>
                <!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <feed xmlns="http://www.w3.org/2005/Atom"><entry><title>&xxe;</title></entry></feed>
                """;
        assertThatThrownBy(() -> ReleaseFeedParser.parseAtomOrRss(hostile))
                .isInstanceOf(RookException.class);
    }

    @Test
    void splitsMarkdownChangelogsIntoEntries() {
        String markdown =
                """
                # Changelog

                ## [2.0.0] - 2026-07-01
                ### Removed
                - Removed `Old`

                ## [1.9.0] - 2026-05-01
                ### Added
                - Things
                """;
        List<ReleaseEntry> entries = ReleaseFeedParser.parseMarkdownChangelog(markdown);
        assertThat(entries).extracting(ReleaseEntry::version).containsExactly("2.0.0", "1.9.0");
        assertThat(entries.get(0).body()).contains("Removed `Old`");
        assertThat(entries.get(0).publishedAt()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(entries.get(1).body()).doesNotContain("Removed");
    }

    @Test
    void versionPrefixIsStripped() {
        assertThat(ReleaseEntry.normalizeVersion("v1.2.3")).isEqualTo("1.2.3");
        assertThat(ReleaseEntry.normalizeVersion("1.2.3")).isEqualTo("1.2.3");
        assertThat(ReleaseEntry.normalizeVersion("vendor-1")).isEqualTo("vendor-1");
    }
}
