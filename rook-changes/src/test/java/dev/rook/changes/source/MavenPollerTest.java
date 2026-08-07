package dev.rook.changes.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.changes.http.FetchPolicy;
import dev.rook.changes.http.SafeHttpFetcher;
import dev.rook.core.change.Attr;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import dev.rook.core.json.Json;
import dev.rook.testkit.TestJars;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenPollerTest {

    private static final String METADATA_PATH = "/com/acme/acme-sdk/maven-metadata.xml";

    @TempDir Path dir;

    private FakeRepoServer repo;
    private SafeHttpFetcher fetcher;
    private InMemorySnapshots snapshots;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = new FakeRepoServer();
        fetcher = new SafeHttpFetcher(FetchPolicy.strict().allowingLocalHttp());
        snapshots = new InMemorySnapshots();
    }

    @AfterEach
    void tearDown() {
        fetcher.close();
        repo.close();
    }

    private MavenPoller poller(ReleaseNotesProvider notes) {
        return new MavenPoller(fetcher, fetcher, snapshots, notes, clock);
    }

    private static Map<String, String> sources(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("com.acme.Client", "package com.acme; public class Client { " + body + " }");
        return map;
    }

    private void publish(String version, String body) throws IOException {
        Path jar = TestJars.build(dir, "acme-sdk-" + version, sources(body));
        repo.put(
                "/com/acme/acme-sdk/" + version + "/acme-sdk-" + version + ".jar",
                Files.readAllBytes(jar));
    }

    private void metadata(String... versions) {
        StringBuilder xml =
                new StringBuilder(
                        "<metadata><groupId>com.acme</groupId><artifactId>acme-sdk</artifactId><versioning><versions>");
        for (String version : versions) {
            xml.append("<version>").append(version).append("</version>");
        }
        xml.append("</versions></versioning></metadata>");
        repo.putText(METADATA_PATH, xml.toString());
    }

    private WatchedSource source(String etag, String lastVersion, Map<String, Object> extra) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("groupId", "com.acme");
        config.put("artifactId", "acme-sdk");
        config.putAll(extra);
        return new WatchedSource(
                "acme-sdk",
                SourceKind.MAVEN,
                "Acme SDK",
                repo.base(),
                Json.tree(config),
                etag,
                lastVersion,
                null,
                null,
                900,
                true);
    }

    private WatchedSource source(String etag, String lastVersion) {
        return source(etag, lastVersion, Map.of());
    }

    private PollOutcome pollOnce(MavenPoller poller, WatchedSource source) {
        PollOutcome outcome = poller.poll(source);
        outcome.snapshots().forEach(snapshots::save);
        return outcome;
    }

    private static final String V1 =
            "public String charge(long amount) { return \"\"; } public void close() {}";
    private static final String V1_1 =
            "public String charge(long amount) { return \"\"; } public void close() {} public void refund(String id) {}";
    private static final String V2 =
            "public String createCharge(long amount) { return \"\"; } public void refund(String id) {}";

    @Test
    void theFirstPollBaselinesTheLatestStableVersion() throws IOException {
        metadata("1.0.0", "1.1.0", "2.0.0-rc1");
        publish("1.1.0", V1_1);
        PollOutcome outcome = pollOnce(poller(ReleaseNotesProvider.none()), source(null, null));
        assertThat(outcome.status()).isEqualTo(PollOutcome.Status.BASELINE);
        assertThat(outcome.lastVersion()).isEqualTo("1.1.0");
        assertThat(outcome.snapshots()).hasSize(1);
        assertThat(outcome.changeSets()).isEmpty();
        assertThat(repo.hits("/com/acme/acme-sdk/1.0.0/acme-sdk-1.0.0.jar")).isZero();
    }

    @Test
    void noNewVersionsMeansNoChange() throws IOException {
        metadata("1.0.0");
        publish("1.0.0", V1);
        MavenPoller poller = poller(ReleaseNotesProvider.none());
        PollOutcome first = pollOnce(poller, source(null, null));
        PollOutcome second = pollOnce(poller, source(first.etag(), first.lastVersion()));
        assertThat(second.status()).isEqualTo(PollOutcome.Status.UNCHANGED);
        assertThat(second.lastVersion()).isEqualTo("1.0.0");
    }

    @Test
    void aNewVersionProducesAChangeSetFromTheStoredSurface() throws IOException {
        metadata("1.1.0");
        publish("1.1.0", V1_1);
        MavenPoller poller = poller(ReleaseNotesProvider.none());
        PollOutcome first = pollOnce(poller, source(null, null));
        metadata("1.1.0", "2.0.0");
        publish("2.0.0", V2);
        PollOutcome second = pollOnce(poller, source(null, first.lastVersion()));
        assertThat(second.status()).isEqualTo(PollOutcome.Status.CHANGED);
        assertThat(second.changeSets()).hasSize(1);
        ChangeSet set = second.changeSets().get(0);
        assertThat(set.fromVersion()).isEqualTo("1.1.0");
        assertThat(set.toVersion()).isEqualTo("2.0.0");
        assertThat(set.sourceKind()).isEqualTo(SourceKind.MAVEN);
        assertThat(set.events())
                .anyMatch(
                        e ->
                                e.kind() == ChangeKind.METHOD_RENAMED
                                        && "createCharge".equals(e.attribute(Attr.REPLACEMENT)));
        assertThat(set.events()).anyMatch(e -> e.kind() == ChangeKind.METHOD_REMOVED);
        assertThat(second.lastVersion()).isEqualTo("2.0.0");
        assertThat(repo.hits("/com/acme/acme-sdk/1.1.0/acme-sdk-1.1.0.jar")).isEqualTo(1);
    }

    @Test
    void severalNewVersionsAreChainedInOrder() throws IOException {
        metadata("1.0.0");
        publish("1.0.0", V1);
        MavenPoller poller = poller(ReleaseNotesProvider.none());
        PollOutcome first = pollOnce(poller, source(null, null));
        metadata("2.0.0", "1.0.0", "1.1.0");
        publish("1.1.0", V1_1);
        publish("2.0.0", V2);
        PollOutcome second = pollOnce(poller, source(null, first.lastVersion()));
        assertThat(second.changeSets()).hasSize(2);
        assertThat(second.changeSets().get(0).fromVersion()).isEqualTo("1.0.0");
        assertThat(second.changeSets().get(0).toVersion()).isEqualTo("1.1.0");
        assertThat(second.changeSets().get(1).fromVersion()).isEqualTo("1.1.0");
        assertThat(second.changeSets().get(1).toVersion()).isEqualTo("2.0.0");
        assertThat(second.lastVersion()).isEqualTo("2.0.0");
    }

    @Test
    void prereleasesAreSkippedUnlessEnabled() throws IOException {
        metadata("1.0.0", "1.1.0-rc1");
        publish("1.0.0", V1);
        publish("1.1.0-rc1", V1_1);
        MavenPoller poller = poller(ReleaseNotesProvider.none());
        PollOutcome first = pollOnce(poller, source(null, null));
        PollOutcome stable = pollOnce(poller, source(null, first.lastVersion()));
        assertThat(stable.status()).isEqualTo(PollOutcome.Status.UNCHANGED);
        PollOutcome withPre =
                pollOnce(
                        poller,
                        source(null, first.lastVersion(), Map.of("includePrerelease", true)));
        assertThat(withPre.lastVersion()).isEqualTo("1.1.0-rc1");
    }

    @Test
    void aSinglePollProcessesAtMostFiveVersions() throws IOException {
        metadata("1.0.0");
        publish("1.0.0", V1);
        MavenPoller poller = poller(ReleaseNotesProvider.none());
        PollOutcome first = pollOnce(poller, source(null, null));
        String[] versions = {
            "1.0.0", "1.1.0", "1.2.0", "1.3.0", "1.4.0", "1.5.0", "1.6.0", "1.7.0"
        };
        metadata(versions);
        for (int i = 1; i < versions.length; i++) {
            publish(versions[i], V1 + " public void extra" + i + "() {}");
        }
        PollOutcome second = pollOnce(poller, source(null, first.lastVersion()));
        assertThat(second.changeSets()).hasSize(MavenPoller.MAX_VERSIONS_PER_POLL);
        assertThat(second.lastVersion()).isEqualTo("1.5.0");
    }

    @Test
    void aMissingJarOnTheFirstStepFailsThePoll() throws IOException {
        metadata("1.0.0");
        publish("1.0.0", V1);
        MavenPoller poller = poller(ReleaseNotesProvider.none());
        PollOutcome first = pollOnce(poller, source(null, null));
        metadata("1.0.0", "1.1.0");
        assertThatThrownBy(() -> poller.poll(source(null, first.lastVersion())))
                .isInstanceOfSatisfying(
                        RookException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAVAILABLE));
    }

    @Test
    void aMissingJarAfterProgressKeepsTheProgress() throws IOException {
        metadata("1.0.0");
        publish("1.0.0", V1);
        MavenPoller poller = poller(ReleaseNotesProvider.none());
        PollOutcome first = pollOnce(poller, source(null, null));
        metadata("1.0.0", "1.1.0", "1.2.0");
        publish("1.1.0", V1_1);
        PollOutcome second = pollOnce(poller, source(null, first.lastVersion()));
        assertThat(second.lastVersion()).isEqualTo("1.1.0");
        assertThat(second.changeSets()).hasSize(1);
    }

    @Test
    void metadataNotModifiedShortCircuits() throws IOException {
        metadata("1.0.0");
        publish("1.0.0", V1);
        MavenPoller poller = poller(ReleaseNotesProvider.none());
        PollOutcome first = pollOnce(poller, source(null, null));
        int jarHits = repo.hits("/com/acme/acme-sdk/1.0.0/acme-sdk-1.0.0.jar");
        PollOutcome second = pollOnce(poller, source(first.etag(), first.lastVersion()));
        assertThat(second.status()).isEqualTo(PollOutcome.Status.UNCHANGED);
        assertThat(repo.hits("/com/acme/acme-sdk/1.0.0/acme-sdk-1.0.0.jar")).isEqualTo(jarHits);
    }

    @Test
    void releaseNotesFillReplacementsAndAddAdvisories() throws IOException {
        metadata("1.1.0");
        publish("1.1.0", V1_1);
        String notesBody =
                "### Changed\\n- `Client.close` has been replaced by `Client.shutdown`\\n### Deprecated\\n- `Client.charge` is deprecated, use `Client.createCharge`\\n";
        repo.putText(
                "/repos/acme/acme-sdk/releases/tags/v2.0.0",
                "{\"tag_name\":\"v2.0.0\",\"name\":\"Acme 2\",\"body\":\""
                        + notesBody
                        + "\",\"html_url\":\"https://example.test/r\"}");
        MavenPoller poller = poller(new GitHubReleaseNotes(fetcher, repo.base()));
        WatchedSource base = source(null, null, Map.of("releaseNotesRepo", "acme/acme-sdk"));
        PollOutcome first = pollOnce(poller, base);
        metadata("1.1.0", "2.0.0");
        publish("2.0.0", V2);
        PollOutcome second =
                pollOnce(
                        poller,
                        source(
                                null,
                                first.lastVersion(),
                                Map.of("releaseNotesRepo", "acme/acme-sdk")));
        ChangeSet set = second.changeSets().get(0);
        List<ChangeEvent> events = set.events();
        assertThat(events).anyMatch(e -> e.kind().isAdvisory());
        assertThat(events)
                .anyMatch(
                        e ->
                                e.kind() == ChangeKind.METHOD_REMOVED
                                        && "Client.shutdown".equals(e.attribute(Attr.REPLACEMENT)));
        assertThat(set.notes()).isNotEmpty();
        assertThat(set.notes().get(0).url()).isEqualTo("https://example.test/r");
    }

    @Test
    void releaseNoteFailuresDoNotBlockTheChangeSet() throws IOException {
        metadata("1.1.0");
        publish("1.1.0", V1_1);
        repo.forceStatus("/repos/acme/acme-sdk/releases/tags/v2.0.0", 500);
        MavenPoller poller = poller(new GitHubReleaseNotes(fetcher, repo.base()));
        Map<String, Object> extra = Map.of("releaseNotesRepo", "acme/acme-sdk");
        PollOutcome first = pollOnce(poller, source(null, null, extra));
        metadata("1.1.0", "2.0.0");
        publish("2.0.0", V2);
        PollOutcome second = pollOnce(poller, source(null, first.lastVersion(), extra));
        assertThat(second.changeSets()).hasSize(1);
        assertThat(second.changeSets().get(0).events()).noneMatch(e -> e.kind().isAdvisory());
    }

    @Test
    void sourcesWithoutCoordinatesAreRejected() {
        WatchedSource bad =
                new WatchedSource(
                        "bad",
                        SourceKind.MAVEN,
                        "bad",
                        repo.base(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        900,
                        true);
        assertThatThrownBy(() -> poller(ReleaseNotesProvider.none()).poll(bad))
                .isInstanceOf(RookException.class)
                .hasMessageContaining("groupId");
    }

    @Test
    void metadataWithADoctypeIsRefused() {
        repo.putText(
                METADATA_PATH,
                "<?xml version=\"1.0\"?><!DOCTYPE metadata [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><metadata><versioning><versions><version>&x;</version></versions></versioning></metadata>");
        assertThatThrownBy(() -> poller(ReleaseNotesProvider.none()).poll(source(null, null)))
                .isInstanceOf(RookException.class);
    }
}
