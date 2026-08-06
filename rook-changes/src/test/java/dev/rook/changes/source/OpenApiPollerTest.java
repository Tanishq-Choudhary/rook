package dev.rook.changes.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rook.changes.http.FetchPolicy;
import dev.rook.changes.http.SafeHttpFetcher;
import dev.rook.changes.openapi.SpecFixtures;
import dev.rook.core.change.ChangeKind;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.error.ErrorCode;
import dev.rook.core.error.RookException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenApiPollerTest {

    private SpecServer server;
    private SafeHttpFetcher fetcher;
    private InMemorySnapshots snapshots;
    private OpenApiPoller poller;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-09T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        server = new SpecServer();
        fetcher = new SafeHttpFetcher(FetchPolicy.strict().allowingLocalHttp());
        snapshots = new InMemorySnapshots();
        poller = new OpenApiPoller(fetcher, snapshots, clock);
    }

    @AfterEach
    void tearDown() {
        fetcher.close();
        server.close();
    }

    private WatchedSource source(String etag, String lastVersion) {
        return new WatchedSource(
                "acme-api",
                SourceKind.OPENAPI,
                "Acme API",
                server.uri().toString(),
                null,
                etag,
                lastVersion,
                null,
                null,
                900,
                true);
    }

    private PollOutcome pollOnce(WatchedSource source) {
        PollOutcome outcome = poller.poll(source);
        outcome.snapshots().forEach(snapshots::save);
        return outcome;
    }

    @Test
    void theFirstPollRecordsABaselineAndNoChanges() {
        server.serve(SpecFixtures.resource("acme-v1.yaml"));
        PollOutcome outcome = pollOnce(source(null, null));
        assertThat(outcome.status()).isEqualTo(PollOutcome.Status.BASELINE);
        assertThat(outcome.changeSets()).isEmpty();
        assertThat(outcome.snapshots()).hasSize(1);
        assertThat(outcome.lastVersion()).isEqualTo("1.4.0");
        assertThat(outcome.etag()).isNotNull();
    }

    @Test
    void anIdenticalSecondPollChangesNothing() {
        server.serve(SpecFixtures.resource("acme-v1.yaml"));
        PollOutcome first = pollOnce(source(null, null));
        PollOutcome second = pollOnce(source(null, first.lastVersion()));
        assertThat(second.status()).isEqualTo(PollOutcome.Status.UNCHANGED);
        assertThat(second.snapshots()).isEmpty();
        assertThat(snapshots.all()).hasSize(1);
    }

    @Test
    void conditionalRequestsHonourNotModified() {
        server.serve(SpecFixtures.resource("acme-v1.yaml"));
        PollOutcome first = pollOnce(source(null, null));
        PollOutcome second = pollOnce(source(first.etag(), first.lastVersion()));
        assertThat(second.status()).isEqualTo(PollOutcome.Status.UNCHANGED);
        assertThat(second.etag()).isEqualTo(first.etag());
        assertThat(server.requests()).isEqualTo(2);
    }

    @Test
    void aNewVersionYieldsAChangeSetFromTheLastSnapshot() {
        server.serve(SpecFixtures.resource("acme-v1.yaml"));
        PollOutcome first = pollOnce(source(null, null));
        server.serve(SpecFixtures.resource("acme-v2.yaml"));
        PollOutcome second = pollOnce(source(first.etag(), first.lastVersion()));
        assertThat(second.status()).isEqualTo(PollOutcome.Status.CHANGED);
        assertThat(second.changeSets()).hasSize(1);
        ChangeSet set = second.changeSets().get(0);
        assertThat(set.fromVersion()).isEqualTo("1.4.0");
        assertThat(set.toVersion()).isEqualTo("2.0.0");
        assertThat(set.sourceKind()).isEqualTo(SourceKind.OPENAPI);
        assertThat(set.events()).extracting(e -> e.kind()).contains(ChangeKind.OPERATION_REMOVED);
        assertThat(set.requiresMigration()).isTrue();
        assertThat(second.lastVersion()).isEqualTo("2.0.0");
    }

    @Test
    void contentChangesWithoutAVersionBumpGetAContentLabel() {
        String v1 = SpecFixtures.resource("acme-v1.yaml");
        server.serve(v1);
        PollOutcome first = pollOnce(source(null, null));
        String edited =
                v1.replace(
                        "        description:\n          type: string\n        created:",
                        "        description:\n          type: string\n        memo:\n          type: string\n        created:");
        assertThat(edited).isNotEqualTo(v1);
        server.serve(edited);
        PollOutcome second = pollOnce(source(first.etag(), first.lastVersion()));
        assertThat(second.lastVersion()).startsWith("1.4.0+");
        assertThat(second.changeSets()).hasSize(1);
        assertThat(second.changeSets().get(0).events())
                .anyMatch(e -> e.subject().name().equals("Charge.memo"));
    }

    @Test
    void contentChangesWithNoApiImpactStillAdvanceTheSnapshot() {
        String v1 = SpecFixtures.resource("acme-v1.yaml");
        server.serve(v1);
        PollOutcome first = pollOnce(source(null, null));
        server.serve(v1.replace("title: Acme Payments", "title: Acme Payments API"));
        PollOutcome second = pollOnce(source(first.etag(), first.lastVersion()));
        assertThat(second.changeSets()).isEmpty();
        assertThat(second.snapshots()).hasSize(1);
        assertThat(second.lastVersion()).startsWith("1.4.0+");
    }

    @Test
    void revertedContentReusesTheOldSnapshotAndDiffsAgainstTheCurrentOne() {
        String v1 = SpecFixtures.resource("acme-v1.yaml");
        String v2 = SpecFixtures.resource("acme-v2.yaml");
        server.serve(v1);
        PollOutcome first = pollOnce(source(null, null));
        server.serve(v2);
        PollOutcome second = pollOnce(source(null, first.lastVersion()));
        server.serve(v1);
        PollOutcome third = pollOnce(source(null, second.lastVersion()));
        assertThat(third.snapshots()).isEmpty();
        assertThat(third.lastVersion()).isEqualTo("1.4.0");
        assertThat(third.changeSets()).hasSize(1);
        assertThat(third.changeSets().get(0).fromVersion()).isEqualTo("2.0.0");
        assertThat(third.changeSets().get(0).toVersion()).isEqualTo("1.4.0");
    }

    @Test
    void invalidSpecificationsAreRejected() {
        server.serve("swagger: '2.0'");
        assertThatThrownBy(() -> pollOnce(source(null, null))).isInstanceOf(RookException.class);
    }

    @Test
    void upstreamFailuresAreReportedAsUnavailable() {
        server.fail(500);
        assertThatThrownBy(() -> pollOnce(source(null, null)))
                .isInstanceOfSatisfying(
                        RookException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAVAILABLE));
    }

    @Test
    void consecutivePollsDiffAgainstTheMostRecentContent() {
        String v1 = SpecFixtures.resource("acme-v1.yaml");
        String v2 = SpecFixtures.resource("acme-v2.yaml");
        String v3 =
                v2.replace("version: 2.0.0", "version: 2.1.0")
                        .replace("        receipt_url:\n          type: string\n", "");
        server.serve(v1);
        PollOutcome first = pollOnce(source(null, null));
        server.serve(v2);
        PollOutcome second = pollOnce(source(null, first.lastVersion()));
        server.serve(v3);
        PollOutcome third = pollOnce(source(null, second.lastVersion()));
        assertThat(third.changeSets()).hasSize(1);
        assertThat(third.changeSets().get(0).fromVersion()).isEqualTo("2.0.0");
        assertThat(third.changeSets().get(0).toVersion()).isEqualTo("2.1.0");
    }
}
