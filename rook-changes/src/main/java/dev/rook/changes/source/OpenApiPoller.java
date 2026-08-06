package dev.rook.changes.source;

import dev.rook.changes.http.FetchResult;
import dev.rook.changes.http.SafeHttpFetcher;
import dev.rook.changes.openapi.OpenApiDiffer;
import dev.rook.changes.openapi.OpenApiDocument;
import dev.rook.changes.openapi.OpenApiLoader;
import dev.rook.core.change.ChangeEvent;
import dev.rook.core.change.ChangeSet;
import dev.rook.core.change.SourceKind;
import dev.rook.core.error.RookException;
import dev.rook.core.hash.Hashes;
import dev.rook.core.id.Ids;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OpenApiPoller implements SourcePoller {

    private final SafeHttpFetcher fetcher;
    private final SnapshotStore snapshots;
    private final Clock clock;

    public OpenApiPoller(SafeHttpFetcher fetcher, SnapshotStore snapshots, Clock clock) {
        this.fetcher = fetcher;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override
    public SourceKind kind() {
        return SourceKind.OPENAPI;
    }

    @Override
    public PollOutcome poll(WatchedSource source) {
        Map<String, String> headers =
                source.etag() == null ? Map.of() : Map.of("If-None-Match", source.etag());
        FetchResult result = fetcher.get(URI.create(source.location()), headers);
        if (result.notModified()) {
            return PollOutcome.unchanged(source.etag(), source.lastVersion());
        }
        if (!result.ok()) {
            throw RookException.unavailable(
                    "upstream answered " + result.status() + " for " + source.location(), null);
        }
        String etag = result.etag().orElse(null);
        String hash = Hashes.sha256Hex(result.body());

        Optional<Snapshot> current =
                source.lastVersion() == null
                        ? Optional.empty()
                        : snapshots.byVersion(source.id(), source.lastVersion());
        if (current.isPresent() && current.get().contentHash().equals(hash)) {
            return PollOutcome.unchanged(etag, source.lastVersion());
        }

        OpenApiDocument next = OpenApiLoader.load(result.body());
        Optional<Snapshot> known = snapshots.byHash(source.id(), hash);
        String version = known.map(Snapshot::version).orElseGet(() -> label(source, next, hash));
        Snapshot snapshot =
                known.orElseGet(
                        () ->
                                new Snapshot(
                                        Ids.newId(),
                                        source.id(),
                                        version,
                                        hash,
                                        result.body(),
                                        clock.instant()));
        List<Snapshot> toSave = known.isPresent() ? List.of() : List.of(snapshot);

        if (current.isEmpty()) {
            return new PollOutcome(PollOutcome.Status.BASELINE, List.of(), toSave, etag, version);
        }

        OpenApiDocument previous = OpenApiLoader.load(current.get().content());
        List<ChangeEvent> events =
                OpenApiDiffer.diff(source.id(), current.get().version(), version, previous, next);
        List<ChangeSet> sets =
                events.isEmpty()
                        ? List.of()
                        : List.of(
                                new ChangeSet(
                                        Ids.newId(),
                                        source.id(),
                                        SourceKind.OPENAPI,
                                        current.get().version(),
                                        version,
                                        events,
                                        List.of(),
                                        clock.instant()));
        return PollOutcome.changed(sets, toSave, etag, version);
    }

    private String label(WatchedSource source, OpenApiDocument document, String hash) {
        String declared = document.version().isBlank() ? "unversioned" : document.version();
        boolean taken = snapshots.byVersion(source.id(), declared).isPresent();
        return taken ? declared + "+" + hash.substring(0, 8) : declared;
    }
}
